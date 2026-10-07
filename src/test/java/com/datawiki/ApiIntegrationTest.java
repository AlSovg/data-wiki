package com.datawiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datawiki.indexing.IndexWorker;
import com.datawiki.indexing.LuceneIndex;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper json;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    IndexWorker worker;
    @Autowired
    LuceneIndex index;

    @BeforeEach
    void reset() {
        jdbc.sql("truncate users, tags cascade").update();
        index.clear();
        index.commit();
    }

    private String token(String email) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"password123\"}";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        var res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + json.readTree(res.getResponse().getContentAsString()).get("accessToken").asString();
    }

    private String upload(String auth, String name, String markdown) throws Exception {
        var file = new MockMultipartFile("files", name, "text/markdown", markdown.getBytes());
        var res = mvc.perform(multipart("/api/import").file(file).header("Authorization", auth))
                .andExpect(status().isOk()).andReturn();
        JsonNode report = json.readTree(res.getResponse().getContentAsString());
        worker.processPending();
        return report.get("files").get(0).get("documentId").asString();
    }

    @Test
    void requiresTokenAndRejectsDuplicateAndBadCredentials() throws Exception {
        mvc.perform(get("/api/documents")).andExpect(status().isUnauthorized());
        token("a@example.com");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"A@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@example.com\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void usersAreIsolatedFromEachOther() throws Exception {
        String alice = token("alice@example.com");
        String bob = token("bob@example.com");
        String id = upload(alice, "secret.md", "# Secret\n\nquarterly budget");

        mvc.perform(get("/api/documents/" + id).header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Secret")) // @JsonUnwrapped meta
                .andExpect(jsonPath("$.content").exists());
        mvc.perform(get("/api/documents/" + id).header("Authorization", bob)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/documents/" + id).header("Authorization", bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/search").param("q", "budget").header("Authorization", bob))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/search").param("q", "budget").header("Authorization", alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/stats").header("Authorization", bob)).andExpect(jsonPath("$.documents").value(0));
    }

    @Test
    void editCreatesVersionAndDeleteRemovesFromSearch() throws Exception {
        String auth = token("alice@example.com");
        String id = upload(auth, "n.md", "# Note\n\nfirst text");

        mvc.perform(put("/api/documents/" + id).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"# Note\\n\\nsecond text\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/documents/" + id + "/versions").header("Authorization", auth))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/documents/" + id + "/versions/1").header("Authorization", auth))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("first")));

        mvc.perform(delete("/api/documents/" + id).header("Authorization", auth)).andExpect(status().isNoContent());
        worker.processPending();
        mvc.perform(get("/api/search").param("q", "second").header("Authorization", auth))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void shareLinksGrantViewOrEditUntilRevoked() throws Exception {
        String alice = token("alice@example.com");
        String bob = token("bob@example.com");
        String id = upload(alice, "s.md", "# Shared\n\nfirst text");

        mvc.perform(put("/api/documents/" + id + "/links/VIEW").header("Authorization", bob))
                .andExpect(status().isNotFound());
        String view = linkToken(alice, id, "VIEW");
        String edit = linkToken(alice, id, "EDIT");
        assertThat(linkToken(alice, id, "VIEW")).isEqualTo(view);

        mvc.perform(get("/api/shared/" + view)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/shared/" + view).header("Authorization", bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Shared"))
                .andExpect(jsonPath("$.permission").value("VIEW"));
        String body = "{\"content\":\"# Shared\\n\\nsecond text\"}";
        mvc.perform(put("/api/shared/" + view).header("Authorization", bob)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/shared/" + edit).header("Authorization", bob)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/documents/" + id + "/versions").header("Authorization", alice))
                .andExpect(jsonPath("$[0].createdBy").value(jdbc.sql("select id::text from users where email = :e")
                        .param("e", "bob@example.com").query(String.class).single()));

        mvc.perform(delete("/api/documents/" + id + "/links/EDIT").header("Authorization", alice))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/shared/" + edit).header("Authorization", bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/" + id + "/links").header("Authorization", alice))
                .andExpect(jsonPath("$.length()").value(1));
    }

    private String linkToken(String auth, String id, String permission) throws Exception {
        var res = mvc.perform(put("/api/documents/" + id + "/links/" + permission).header("Authorization", auth))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("token").asString();
    }

    @Test
    void importsZipAndReportsPerFileStatus() throws Exception {
        String auth = token("alice@example.com");
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : new String[][] {{"a.md", "# A\n\nalpha"}, {"b.txt", "plain"}, {"c.md", "# A\n\nalpha"}}) {
                zip.putNextEntry(new ZipEntry(entry[0]));
                zip.write(entry[1].getBytes());
                zip.closeEntry();
            }
        }
        var file = new MockMultipartFile("files", "docs.zip", "application/zip", bytes.toByteArray());

        var res = mvc.perform(multipart("/api/import").file(file).header("Authorization", auth))
                .andExpect(status().isOk()).andReturn();

        JsonNode report = json.readTree(res.getResponse().getContentAsString());
        assertThat(report.get("created").asInt()).isEqualTo(1);
        assertThat(report.get("duplicates").asInt()).isEqualTo(1);
        assertThat(report.get("files").get(1).get("status").asString()).isEqualTo("skipped");
    }

    @Test
    void adminEndpointIsForAdminOnly() throws Exception {
        String user = token("user@example.com");
        String admin = token("admin@example.com"); // app.admin-email in test properties

        mvc.perform(post("/api/admin/reindex").header("Authorization", user)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/reindex").header("Authorization", admin)).andExpect(status().isAccepted());
    }

    @Test
    void savesSearchSettingsPerUser() throws Exception {
        String auth = token("alice@example.com");

        mvc.perform(put("/api/settings/search").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"hybrid\",\"weights\":{\"bm25\":2,\"tfidf\":1}}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/settings/search").header("Authorization", auth))
                .andExpect(jsonPath("$.method").value("hybrid"));
        mvc.perform(put("/api/settings/search").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"magic\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/search/methods").header("Authorization", auth)).andExpect(status().isOk());
    }
}
