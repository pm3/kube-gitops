package eu.aston.gitops;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import eu.aston.gitops.model.OciData;
import eu.aston.gitops.service.KubeService;
import eu.aston.gitops.service.RepoService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.zip.GZIPOutputStream;

/**
 * Unit test for OCI pull: in-memory HTTP server returns fake manifest and one layer (tar.gz).
 * Extraction uses "tar" – test is enabled on Linux/macOS (CI); on Windows it is skipped.
 * <p>
 * To test against a real registry (optional): run with env OCI_TEST_IMAGE=e.g. aidaston.azurecr.io/edu-infra-deploy:latest
 * and OCI_TEST_AUTH=namespace/secret (or leave unset for public image). Not implemented here – add an integration test if needed.
 */
public class TestOciPull {

    private static final String LAYER_DIGEST = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    /**
     * Build minimal tar.gz with one file "test.txt" containing "ok".
     */
    private static byte[] minimalTarGz() throws IOException {
        byte[] content = "ok".getBytes(StandardCharsets.UTF_8);
        int size = content.length;
        byte[] header = new byte[512];
        // filename at 0 (100 bytes)
        byte[] name = "test.txt".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(name, 0, header, 0, Math.min(name.length, 99));
        // size at 124, 12 chars octal
        String sizeOct = String.format("%011o", size).substring(0, 11);
        System.arraycopy(sizeOct.getBytes(StandardCharsets.UTF_8), 0, header, 124, sizeOct.length());
        // type at 156: '0' = normal file
        header[156] = '0';
        // ustar magic at 257
        System.arraycopy("ustar".getBytes(StandardCharsets.UTF_8), 0, header, 257, 5);
        // checksum at 148 (8 bytes): sum of 512 bytes with these 8 as spaces, then octal
        for (int i = 148; i < 156; i++) header[i] = ' ';
        int sum = 0;
        for (byte b : header) sum += (b & 0xff);
        String sumOct = String.format("%06o", sum);
        System.arraycopy(sumOct.getBytes(StandardCharsets.UTF_8), 0, header, 148, 6);
        header[154] = 0;
        header[155] = ' ';

        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        tar.write(header);
        tar.write(content);
        // pad to 512 block
        int pad = (512 - (512 + size) % 512) % 512;
        if (pad > 0) tar.write(new byte[pad]);

        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gz)) {
            gzip.write(tar.toByteArray());
        }
        return gz.toByteArray();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    public void testOciPullWithMockRegistry() throws Exception {
        byte[] layerBlob = minimalTarGz();
        String manifest = """
                {
                  "schemaVersion": 2,
                  "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                  "config": { "mediaType": "...", "size": 0, "digest": "sha256:cfg" },
                  "layers": [
                    {
                      "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
                      "size": %d,
                      "digest": "%s"
                    }
                  ]
                }
                """.formatted(layerBlob.length, LAYER_DIGEST);

        HttpHandler handler = exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            String contentType = "application/json";
            if (path != null && path.contains("/manifests/")) {
                body = manifest.getBytes(StandardCharsets.UTF_8);
            } else if (path != null && path.contains("/blobs/")) {
                body = layerBlob;
                contentType = "application/octet-stream";
            } else {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        };

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", handler);
        server.start();
        int port = server.getLocalPort();
        try {
            String host = "localhost:" + port;
            KubeService kubeService = new KubeService();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            RepoService repoService = new RepoService(kubeService, client, "http");
            OciData oci = new OciData(host + "/test-repo:latest", null);
            File dir = Files.createTempDirectory("oci-pull-test").toFile();
            try {
                boolean ok = repoService.pull(dir, oci, "default");
                Assertions.assertTrue(ok, "pull should succeed");
                File testFile = new File(dir, "test.txt");
                Assertions.assertTrue(testFile.isFile(), "test.txt should exist");
                Assertions.assertEquals("ok", Files.readString(testFile.toPath()), "content");
            } finally {
                deleteDir(dir);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testOciPullInvalidInput() {
        KubeService kubeService = new KubeService();
        HttpClient client = HttpClient.newBuilder().build();
        RepoService repoService = new RepoService(kubeService, client);
        Assertions.assertFalse(repoService.pull(new File("/tmp/x"), null, "default"));
        Assertions.assertFalse(repoService.pull(new File("/tmp/x"), new OciData("", "s"), "default"));
        Assertions.assertFalse(repoService.pull(new File("/tmp/x"), new OciData("no-slash", null), "default"));
    }

    @Test
    public void testEnsureAuthParsing() throws Exception {
        KubeService kubeService = new KubeService();
        HttpClient client = HttpClient.newBuilder().build();
        RepoService repoService = new RepoService(kubeService, client);
        // namespace/secret
        repoService.ensureAuthForPull("my-ns/my-secret", "default");
        // just secret → uses default
        repoService.ensureAuthForPull("regcred", "flux");
        // no NPE on null/blank
        repoService.ensureAuthForPull(null, "default");
        repoService.ensureAuthForPull("", "default");
    }

    private static void deleteDir(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteDir(c);
        }
        f.delete();
    }
}
