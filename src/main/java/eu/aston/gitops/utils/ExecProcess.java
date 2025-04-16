package eu.aston.gitops.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExecProcess {

    private final static Logger LOGGER = LoggerFactory.getLogger("exec");

    public static final boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");

    public static void execBuilder(File workDir, List<String> arguments, StringBuilder out){
        if(out!=null){
            execStream(workDir, arguments, (c)->out.append(c).append('\n'));
        } else {
            execStream(workDir, arguments, null);
        }
    }

    public static void execStream(File workDir, List<String> arguments, Consumer<String> consumer){

        Process p = null;
        try {
            ProcessBuilder builder = new ProcessBuilder();
            List<String> osargs = new ArrayList<>();
            if (isWindows) {
                osargs.add("cmd.exe");
                osargs.add("/c");
            }
            osargs.addAll(arguments);
            builder.command(osargs);
            builder.directory(workDir);
            builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
            builder.redirectError(ProcessBuilder.Redirect.PIPE);
            LOGGER.debug("{}", String.join(" ", arguments));
            p = builder.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            while (true) {
                String l = br.readLine();
                if (l == null)
                    break;
                if (consumer != null) {
                    consumer.accept(l);
                } else {
                    LOGGER.debug(l);
                }
            }
            br.close();
            BufferedReader br2 = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            while (true) {
                String l = br2.readLine();
                if (l == null)
                    break;
                LOGGER.info("error: {}", l);
            }
            br2.close();
            if (p.waitFor() != 0) {
                LOGGER.warn("{} - exit code {}", String.join(" ", arguments), p.exitValue());
                throw new RuntimeException("exitCode " + p.exitValue());
            }
            p.destroy();
        }catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warn("call process error {} - {}", String.join(" ", arguments), e.getMessage());
            LOGGER.warn("call process stack", e);
        } finally {
            try {
                if(p!=null) p.destroy();
            } catch (Exception ignore) {
            }
        }
    }

    public static void deepDelete(File dir, boolean includeDir) {
        for (File f : Optional.ofNullable(dir.listFiles()).orElse(new File[0])) {
            if (f.isDirectory()) {
                deepDelete(f, true);
            } else if (f.isFile()) {
                f.delete();
            }
        }
        if (includeDir) dir.delete();
    }

}
