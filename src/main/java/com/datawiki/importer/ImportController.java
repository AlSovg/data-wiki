package com.datawiki.importer;

import static com.datawiki.auth.CurrentUser.id;

import java.io.IOException;
import java.security.Principal;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/import")
class ImportController {

    private final ImportService importer;

    ImportController(ImportService importer) {
        this.importer = importer;
    }

    /** Files, a folder (parts named with their relative path) or {@code .zip} archives. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ImportService.Report importFiles(Principal user, @RequestParam("files") List<MultipartFile> files)
            throws IOException {
        return importer.importFiles(id(user), files);
    }
}
