package com.datawiki.admin;

import com.datawiki.indexing.IndexWorker;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Role ADMIN only, see SecurityConfig. */
@RestController
@RequestMapping("/api/admin")
class AdminController {

    private final IndexWorker worker;

    AdminController(IndexWorker worker) {
        this.worker = worker;
    }

    /** Clears the index and queues every live document; the background worker refills it. */
    @PostMapping("/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void reindex() {
        worker.rebuild();
    }
}
