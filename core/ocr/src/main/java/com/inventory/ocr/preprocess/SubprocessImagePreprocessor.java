package com.inventory.ocr.preprocess;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the Python image_preprocess module as a subprocess: stdin = raw image bytes, stdout = JPEG bytes.
 * Requires Python + image_preprocess deps on the same host (e.g. pip install -r image_preprocess/requirements.txt).
 */
@Slf4j
public class SubprocessImagePreprocessor implements ImagePreprocessor {

  private static final int PROCESS_TIMEOUT_SECONDS = 60;

  private final List<String> command;
  private final Path workingDir;

  /**
   * @param command e.g. ["python3", "-m", "image_preprocess"] (no input/output args; uses stdin/stdout)
   * @param workingDir working directory for the process (e.g. project root so "python3 -m image_preprocess" resolves)
   */
  public SubprocessImagePreprocessor(List<String> command, Path workingDir) {
    this.command = command;
    this.workingDir = workingDir;
  }

  @Override
  public byte[] preprocess(byte[] imageBytes) throws IOException {
    ProcessBuilder pb = new ProcessBuilder(command)
        .redirectErrorStream(true)
        .directory(workingDir != null ? workingDir.toFile() : null);
    Process process = pb.start();

    try (OutputStream stdin = process.getOutputStream()) {
      stdin.write(imageBytes);
    }

    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    try (InputStream in = process.getInputStream()) {
      int n;
      while ((n = in.read(buffer)) != -1) {
        stdout.write(buffer, 0, n);
      }
    }

    boolean finished;
    try {
      finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new IOException("Image preprocessing interrupted", e);
    }
    if (!finished) {
      process.destroyForcibly();
      throw new IOException("Image preprocessing subprocess timed out after " + PROCESS_TIMEOUT_SECONDS + "s");
    }
    int exit = process.exitValue();
    if (exit != 0) {
      String err = stdout.toString();
      log.warn("image_preprocess subprocess exited {}: {}", exit, err);
      throw new IOException("Image preprocessing failed (exit " + exit + "): " + err);
    }

    byte[] result = stdout.toByteArray();
    log.debug("Preprocessed image: {} -> {} bytes", imageBytes.length, result.length);
    return result;
  }
}
