package com.copaw.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Generic JSON file storage utility with file locking for concurrent safety.
 *
 * <p>This class provides type-safe JSON file read/write operations using Jackson ObjectMapper.
 * It supports both single object and list operations, with automatic parent directory creation
 * and file locking for concurrent access.</p>
 *
 * @param <T> the type of objects to store
 */
@Component
public class JsonFileStore<T> {
    private static final Logger log = LoggerFactory.getLogger(JsonFileStore.class);

    private final ObjectMapper objectMapper;

    public JsonFileStore() {
        this.objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * Read a single object from a JSON file.
     *
     * @param path  the path to the JSON file
     * @param clazz the class of the object to read
     * @return the read object, or null if the file does not exist or is empty
     */
    public T read(Path path, Class<T> clazz) {
        if (!Files.exists(path)) {
            return null;
        }

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
             FileChannel channel = raf.getChannel();
             FileLock lock = channel.lock(0, Long.MAX_VALUE, true)) {

            String content = raf.readLine();
            if (content == null || content.trim().isEmpty()) {
                return null;
            }

            // Read entire file content
            raf.seek(0);
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = raf.readLine()) != null) {
                sb.append(line);
            }

            if (sb.length() == 0) {
                return null;
            }

            return objectMapper.readValue(sb.toString(), clazz);
        } catch (FileNotFoundException e) {
            return null;
        } catch (IOException e) {
            log.warn("Failed to read JSON from {}: {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * Write a single object to a JSON file.
     *
     * @param path   the path to the JSON file
     * @param object the object to write
     */
    public void write(Path path, T object) {
        if (object == null) {
            return;
        }

        try {
            // Create parent directories if needed
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            // Write to temp file first, then atomic move
            Path tempPath = path.resolveSibling("." + path.getFileName() + ".tmp");

            try (RandomAccessFile raf = new RandomAccessFile(tempPath.toFile(), "rw");
                 FileChannel channel = raf.getChannel();
                 FileLock lock = channel.lock()) {

                raf.setLength(0);
                String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(object);
                raf.writeBytes(json);
            }

            // Atomic move
            Files.move(tempPath, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);

        } catch (IOException e) {
            log.error("Failed to write JSON to {}: {}", path, e.getMessage());
            throw new RuntimeException("Failed to write JSON to " + path, e);
        }
    }

    /**
     * Read a list of objects from a JSON file.
     *
     * @param path          the path to the JSON file
     * @param typeReference the type reference for the list type
     * @return the read list, or an empty list if the file does not exist
     */
    public List<T> readList(Path path, TypeReference<List<T>> typeReference) {
        if (!Files.exists(path)) {
            return Collections.emptyList();
        }

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
             FileChannel channel = raf.getChannel();
             FileLock lock = channel.lock(0, Long.MAX_VALUE, true)) {

            // Read entire file content
            raf.seek(0);
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = raf.readLine()) != null) {
                sb.append(line);
            }

            if (sb.length() == 0) {
                return Collections.emptyList();
            }

            List<T> result = objectMapper.readValue(sb.toString(), typeReference);
            return result != null ? result : Collections.emptyList();
        } catch (FileNotFoundException e) {
            return Collections.emptyList();
        } catch (IOException e) {
            log.warn("Failed to read JSON list from {}: {}", path, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Write a list of objects to a JSON file.
     *
     * @param path  the path to the JSON file
     * @param list  the list to write
     */
    public void writeList(Path path, List<T> list) {
        if (list == null) {
            return;
        }

        try {
            // Create parent directories if needed
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            // Write to temp file first, then atomic move
            Path tempPath = path.resolveSibling("." + path.getFileName() + ".tmp");

            try (RandomAccessFile raf = new RandomAccessFile(tempPath.toFile(), "rw");
                 FileChannel channel = raf.getChannel();
                 FileLock lock = channel.lock()) {

                raf.setLength(0);
                String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(list);
                raf.writeBytes(json);
            }

            // Atomic move
            Files.move(tempPath, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);

        } catch (IOException e) {
            log.error("Failed to write JSON list to {}: {}", path, e.getMessage());
            throw new RuntimeException("Failed to write JSON list to " + path, e);
        }
    }

    /**
     * Get the underlying ObjectMapper for custom configurations.
     *
     * @return the ObjectMapper instance
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
