package eu.aston.gitops.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

public class ChecksumDir {

    static public long checksumString(String s){
        CRC32 crc32 = new CRC32();
        crc32.update(s.getBytes(StandardCharsets.UTF_8));
        return crc32.getValue();
    }

    static public long checksumDir(File dir) throws IOException {
        CRC32 crc32 = new CRC32();
        checksum(dir, crc32);
        return crc32.getValue();
    }

    static public void checksum(File f, CRC32 crc32) throws IOException {
        if (f.isDirectory()) {
            byte[] nBytes = f.getName().getBytes(StandardCharsets.UTF_8);
            crc32.update(nBytes);
            String[] files = f.list();
            if(files!=null) {
                Arrays.sort(files);
                for (String fName : files) {
                    if (fName.startsWith("."))
                        continue;
                    File f2 = new File(f, fName);
                    checksum(f2, crc32);
                }
            }
        } else if (f.isFile()) {
            byte[] nBytes = f.getName().getBytes(StandardCharsets.UTF_8);
            crc32.update(nBytes);
            try (InputStream is = new FileInputStream(f)) {
                byte[] buf = new byte[512];
                int len = 0;
                while ((len = is.read(buf)) > 0) {
                    crc32.update(buf, 0, len);
                }
            }
        }
    }
}
