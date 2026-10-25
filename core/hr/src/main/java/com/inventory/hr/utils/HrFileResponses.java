package com.inventory.hr.utils;

import com.inventory.hr.service.HrFile;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** File downloads (E1: {@code ResponseEntity<byte[]>} with {@code Content-Disposition}). */
public final class HrFileResponses {

  private HrFileResponses() {}

  public static ResponseEntity<byte[]> attachment(HrFile file) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType(file.contentType()));
    headers.setContentDisposition(ContentDisposition.attachment()
        .filename(file.fileName(), StandardCharsets.UTF_8).build());
    headers.setContentLength(file.bytes().length);
    // personal pay data: never kept by a browser or proxy cache
    headers.setCacheControl("no-store");
    return ResponseEntity.ok().headers(headers).body(file.bytes());
  }
}
