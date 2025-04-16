package eu.aston.gitops.service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.aston.gitops.utils.ExecProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EncryptService {

    private final static Logger LOGGER = LoggerFactory.getLogger(EncryptService.class);

    private final Map<String, SecretKeySpec> secretKeySpecMap = new ConcurrentHashMap<>();

    public void decryptDir(File srcDir, File desDir, String key) throws Exception {
        ExecProcess.deepDelete(desDir, false);
        SecretKeySpec secretKeySpec = secretKeySpecMap.computeIfAbsent(key, this::createSecretKey);
        decryptDir(srcDir, desDir, secretKeySpec);
    }

    public String encrypt(String strToEncrypt, String key) throws Exception {
        SecretKeySpec secretKeySpec = secretKeySpecMap.computeIfAbsent(key, this::createSecretKey);
        return encryptRaw(strToEncrypt.getBytes(StandardCharsets.UTF_8), secretKeySpec);
    }

    private SecretKeySpec createSecretKey(String key) {
        try{
            String rawKey = new String(Base64.getDecoder().decode(key), StandardCharsets.UTF_8);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            KeySpec spec = new PBEKeySpec(rawKey.toCharArray(), "{+fX}WpwFrbL63a6".getBytes(), 256, 256);
            SecretKey tmp = factory.generateSecret(spec);
            return new SecretKeySpec(tmp.getEncoded(), "AES");
        }catch (Exception e){
            LOGGER.warn("parse secret key error {} - {}", key, e.getMessage());
            LOGGER.debug("parse secret key stack ", e);
        }
        return null;
    }

    private String encryptRaw(byte[] data, SecretKeySpec secretKey) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        return Base64.getEncoder().encodeToString(cipher.doFinal(data));
    }


    public byte[] decryptRaw(String strToDecrypt, SecretKeySpec secretKey) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        return cipher.doFinal(Base64.getDecoder().decode(strToDecrypt));
    }

    public String decrypt(String strToDecrypt, SecretKeySpec secretKey) {
        try{
            byte[] raw = decryptRaw(strToDecrypt, secretKey);
            return new String(raw, StandardCharsets.UTF_8);
        }catch (Exception e){
            throw new RuntimeException("invalid encription data "+strToDecrypt, e);
        }
    }

    public void decryptDir(File dirFrom, File dirTo, SecretKeySpec secretKey) throws Exception {
        dirTo.mkdir();
        for (File f : Optional.ofNullable(dirFrom.listFiles()).orElse(new File[0])) {
            if (f.getName().startsWith(".git")) continue;
            File f2 = new File(dirTo, f.getName());
            if (f.isDirectory()) {
                decryptDir(f, f2, secretKey);
            } else if (f.isFile()) {
                try {
                    decryptFile(f, f2, secretKey);
                } catch (Exception e) {
                    LOGGER.warn("error copy file {} => {} - {}",  f.getAbsolutePath(), f2.getAbsolutePath(), e.getMessage());
                    throw e;
                }
            } else {
                LOGGER.info("ignore {}", f.getAbsolutePath());
            }
        }
    }

    public void decryptFile(File fileFrom, File fileTo, SecretKeySpec secretKey) throws IOException {
        String content = Files.readString(fileFrom.toPath(), StandardCharsets.UTF_8);
        //check binary enc file
        if (content.matches("^enc\\([a-zA-Z0-9+/=]+\\)$")) {
            try {
                byte[] data = decryptRaw(content.substring(4, content.length() - 1), secretKey);
                try (FileOutputStream fos = new FileOutputStream(fileTo)) {
                    fos.write(data);
                }
            } catch (Exception e) {
                throw new IOException("invalid encryption binary data [" + fileFrom.getName() + "] " + e.getMessage(), e);
            }
            return;
        }
        //check parse content
        content = parseContentEnc(content, secretKey);
        if (content != null) {
            try (FileOutputStream fos = new FileOutputStream(fileTo)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        //copy raw
        Files.copy(fileFrom.toPath(), fileTo.toPath());
    }

    private String parseContentEnc(String content, SecretKeySpec secretKey) throws IOException {
        Pattern p = Pattern.compile("enc\\(([a-zA-Z0-9+/=]+)\\)");
        Matcher m = p.matcher(content);
        String content2 = m.replaceAll(r -> decrypt(r.group(1), secretKey));
        return !content.equals(content2) ? content2 : null;
    }

    private byte[] encryptHtml = null;

    public byte[] encryptHtml(){
        if(encryptHtml==null){
            try(InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream("encrypt.html")) {
                if(is!=null){
                    encryptHtml = is.readAllBytes();
                }
            }catch (Exception e){
                LOGGER.error("read encrypt.html", e);
                encryptHtml = new byte[0];
            }
        }
        return encryptHtml;
    }
}
