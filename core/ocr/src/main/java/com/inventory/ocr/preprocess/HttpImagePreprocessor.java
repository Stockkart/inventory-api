package com.inventory.ocr.preprocess;

import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;

/**
 * Sends image bytes to the preprocess API via multipart/form-data (key "image"),
 * matching the curl contract: --form 'image=@file'
 */
@Slf4j
public class HttpImagePreprocessor implements ImagePreprocessor {

  private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

  private final OkHttpClient okHttpClient;
  private final String preprocessUrl;

  public HttpImagePreprocessor(OkHttpClient okHttpClient, String preprocessUrl) {
    this.okHttpClient = okHttpClient;
    this.preprocessUrl = preprocessUrl;
  }

  @Override
  public byte[] preprocess(byte[] imageBytes) throws IOException {
    if (imageBytes == null || imageBytes.length == 0) {
      throw new IOException("Image bytes are empty, cannot preprocess");
    }
    RequestBody filePart = RequestBody.create(imageBytes, OCTET_STREAM);
    RequestBody multipart = new MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("image", "image.jpg", filePart)
        .build();

    Request request = new Request.Builder()
        .url(preprocessUrl)
        .post(multipart)
        .build();

    try (Response response = okHttpClient.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        ResponseBody errBody = response.body();
        String body = errBody != null ? errBody.string() : "";
        throw new IOException("Preprocess API returned " + response.code() + ": " + body);
      }
      ResponseBody body = response.body();
      if (body == null) {
        throw new IOException("Preprocess API returned empty body");
      }
      byte[] result = body.bytes();
      if (result.length == 0) {
        throw new IOException("Preprocess API returned empty body");
      }
      log.debug("Preprocessed image via HTTP: {} -> {} bytes", imageBytes.length, result.length);
      return result;
    }
  }
}
