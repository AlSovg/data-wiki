package com.datawiki.auth;

import static java.nio.charset.StandardCharsets.UTF_8;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Registration and login; both answer with a bearer token. */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    /** bcrypt input is limited to 72 bytes. */
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final String USER = "USER";
    private static final String ADMIN = "ADMIN";

    record Credentials(@NotBlank @Email @Size(max = 255) String email,
                       @NotBlank @Size(min = 8, max = MAX_PASSWORD_BYTES) String password) {
    }

    record AuthToken(String accessToken, String tokenType, long expiresIn) {
    }

    private record Account(UUID id, String passwordHash, String role) {
    }

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwt;
    private final Duration ttl;
    private final String adminEmail;
    /** Compared against when the email is unknown, so that login time does not reveal which emails exist. */
    private final String dummyHash;

    AuthController(JdbcClient jdbc, PasswordEncoder encoder, JwtEncoder jwt,
                   @Value("${app.jwt.ttl-seconds}") long ttlSeconds,
                   @Value("${app.admin-email:}") String adminEmail) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.jwt = jwt;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.adminEmail = adminEmail.strip().toLowerCase(Locale.ROOT);
        this.dummyHash = encoder.encode("dummy-password");
    }

    @PostMapping("/register")
    ResponseEntity<AuthToken> register(@Valid @RequestBody Credentials credentials) {
        if (credentials.password().getBytes(UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException("Password is longer than " + MAX_PASSWORD_BYTES + " bytes");
        }
        String email = normalize(credentials.email());
        UUID id = UUID.randomUUID();
        String role = email.equals(adminEmail) ? ADMIN : USER;
        try {
            jdbc.sql("insert into users (id, email, password_hash, role) values (:id, :email, :hash, :role)")
                    .param("id", id).param("email", email).param("hash", encoder.encode(credentials.password()))
                    .param("role", role).update();
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(token(id, role));
    }

    @PostMapping("/login")
    AuthToken login(@Valid @RequestBody Credentials credentials) {
        Optional<Account> account = jdbc.sql("select id, password_hash, role from users where email = :email")
                .param("email", normalize(credentials.email()))
                .query((rs, n) -> new Account(rs.getObject("id", UUID.class), rs.getString("password_hash"),
                        rs.getString("role")))
                .optional();
        boolean ok = encoder.matches(credentials.password(), account.map(Account::passwordHash).orElse(dummyHash));
        if (account.isEmpty() || !ok) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong email or password");
        }
        return token(account.get().id(), account.get().role());
    }

    private AuthToken token(UUID userId, String role) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().subject(userId.toString()).claim("role", role)
                .issuedAt(now).expiresAt(now.plus(ttl)).build();
        String value = jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new AuthToken(value, "Bearer", ttl.toSeconds());
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
