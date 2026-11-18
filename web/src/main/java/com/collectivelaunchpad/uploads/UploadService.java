package com.collectivelaunchpad.uploads;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.collectivelaunchpad.config.AppProperties;

/**
 * Product photos. Images only (type checked from the file's first bytes, not its name), size
 * limited, stored under generated names in a per-seller folder: sellers/{sellerId}/{uuid}.jpg
 */
@Service
public class UploadService {

    public static final int MAX_FILES_PER_REQUEST = 8;
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Pattern PHOTO_ID = Pattern.compile("^[0-9a-f]{32}\\.(jpg|png|webp)$");

    public record Photo(String id, String path) {
        public String url() {
            return "/uploads/" + path;
        }
    }

    public static class UploadException extends RuntimeException {
        public UploadException(String message) {
            super(message);
        }
    }

    private final Path root;

    public UploadService(AppProperties props) throws IOException {
        this.root = Path.of(props.uploadsDir()).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public Path root() {
        return root;
    }

    public List<Photo> storeAll(long sellerId, List<MultipartFile> files) {
        List<MultipartFile> real = files == null ? List.of() : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (real.size() > MAX_FILES_PER_REQUEST) {
            throw new UploadException("Please choose at most " + MAX_FILES_PER_REQUEST + " photos at a time.");
        }
        // Validate everything before writing anything.
        List<byte[]> contents = new ArrayList<>();
        List<String> exts = new ArrayList<>();
        for (MultipartFile f : real) {
            if (f.getSize() > MAX_BYTES) {
                throw new UploadException("\"" + f.getOriginalFilename() + "\" is larger than 10 MB.");
            }
            byte[] bytes;
            try {
                bytes = f.getBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            String ext = detect(bytes).orElseThrow(() ->
                    new UploadException("\"" + f.getOriginalFilename() + "\" is not a JPG, PNG or WebP photo."));
            contents.add(bytes);
            exts.add(ext);
        }
        List<Photo> stored = new ArrayList<>();
        try {
            Path dir = sellerDir(sellerId);
            Files.createDirectories(dir);
            for (int i = 0; i < contents.size(); i++) {
                String id = UUID.randomUUID().toString().replace("-", "") + "." + exts.get(i);
                Files.write(dir.resolve(id), contents.get(i));
                stored.add(new Photo(id, "sellers/" + sellerId + "/" + id));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return stored;
    }

    /** Finds a photo by the id the agent was given; only within that seller's folder. */
    public Optional<Photo> find(long sellerId, String photoId) {
        if (photoId == null || !PHOTO_ID.matcher(photoId).matches()) {
            return Optional.empty();
        }
        Path file = sellerDir(sellerId).resolve(photoId).normalize();
        if (!file.startsWith(sellerDir(sellerId)) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(new Photo(photoId, "sellers/" + sellerId + "/" + photoId));
    }

    public List<String> photoIds(long sellerId) {
        String[] names = sellerDir(sellerId).toFile().list();
        return names == null ? List.of() : Arrays.stream(names).filter(n -> PHOTO_ID.matcher(n).matches()).sorted().toList();
    }

    /** Reads a stored photo given its relative path, refusing anything outside the uploads folder. */
    public byte[] read(String relativePath) throws IOException {
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root)) {
            throw new IOException("Path outside uploads: " + relativePath);
        }
        return Files.readAllBytes(file);
    }

    public static String mediaType(String path) {
        if (path.endsWith(".png")) {
            return "image/png";
        }
        if (path.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private Path sellerDir(long sellerId) {
        return root.resolve("sellers").resolve(Long.toString(sellerId)).normalize();
    }

    /** JPEG FF D8 FF; PNG 89 50 4E 47; WebP "RIFF....WEBP". */
    static Optional<String> detect(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return Optional.of("jpg");
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return Optional.of("png");
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return Optional.of("webp");
        }
        return Optional.empty();
    }

    static Optional<String> detect(InputStream in) throws IOException {
        return detect(in.readNBytes(12));
    }
}
