package top.ff.utils;

import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import sun.misc.Unsafe;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * bilibili 视频转换工具
 */
public class BilibiliConvertUtil {
    private static final String VIDEO_SUFFIX = "-30080.m4s";
    private static final String AUDIO_SUFFIX = "-30280.m4s";
    private static final String FFMPEG_HOME = "FFMPEG_HOME";
    private static final Set<String> RECORD_SET = new HashSet<>(1000);

    public static void main(String[] args) throws Exception {
        System.setProperty(FFMPEG_HOME, "D:\\Application\\ffmpeg");
        convert(new ThreadPoolExecutor(5, 10,
                5, TimeUnit.SECONDS, new LinkedBlockingQueue<>()),
                "C:\\Users\\bowin\\Videos\\bilibili",
                "C:\\Users\\bowin\\Videos\\Test");
    }

    public static void convert(ExecutorService executorService, String folderPath, String saveFolderPath) throws Exception {
        File saveFolder = new File(saveFolderPath);
        if (!saveFolder.exists()) {
            if (!saveFolder.mkdirs()) {
                throw new IllegalStateException("创建文件夹失败");
            }
        }
        File[] files = new File(folderPath).listFiles();
        if (files == null) {
            return;
        }
        long start = System.currentTimeMillis();
        LongAdder lad = new LongAdder();
        CountDownLatch countDownLatch = new CountDownLatch(files.length);
        for (File eachFolder : files) {
            if (eachFolder.isDirectory()) {
                transferFiles(countDownLatch, lad, executorService, eachFolder, saveFolder);
            } else {
                countDownLatch.countDown();
            }
        }
        Unsafe unsafe = null;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);
        } catch (Exception e) {
            e.printStackTrace();
        }
        while (countDownLatch.getCount() != 0) {
            sleep(unsafe, 5);
        }
        executorService.shutdown();
        System.out.println("完成转换！");
        System.out.println("总耗时：" + (System.currentTimeMillis() - start) + " 毫秒");
        System.out.println("转换文件大小：" + lad.sum());
    }

    private static void sleep(Unsafe unsafe, int duration) {
        if (unsafe == null) {
            try {
                Thread.sleep(TimeUnit.SECONDS.convert(duration, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } else {
            unsafe.park(true, TimeUnit.SECONDS.convert(duration, TimeUnit.NANOSECONDS));
        }
    }

    private static void transferFiles(CountDownLatch countDownLatch, LongAdder lad, ExecutorService executorService, File eachFolder, File saveFolder) throws Exception {
        File[] files = eachFolder.listFiles();
        if (files == null) {
            countDownLatch.countDown();
            return;
        }
        Map<String, File> fileMap = Arrays.stream(files).collect(Collectors.toMap(
                File::getName,
                file -> file
        ));
        File infoFile = fileMap.get("videoInfo.json");
        if (infoFile == null) {
            countDownLatch.countDown();
            return;
        }
        JSONObject info = JSON.parseObject(FileUtil.readString(infoFile, StandardCharsets.UTF_8));
        long videoSize = info.getLong("totalSize");
        File dir = new File(saveFolder, info.getString("uname"));
        File finalFile = new File(dir, info.getString("p") + "-" + info.getString("title")+".mp4");
        if (finalFile.exists()) {
            if (finalFile.length() == videoSize) {
                countDownLatch.countDown();
                return;
            }
            finalFile.delete();
        }
        // 每个文件删除开头九个0
        InputStream audio = null;
        InputStream video = null;
        for (Map.Entry<String, File> fileEntry : fileMap.entrySet()) {
            if (fileEntry.getKey().endsWith(VIDEO_SUFFIX)) {
                video = FileUtil.getInputStream(fileEntry.getValue());
                video.skip(9);
            }
            else if (fileEntry.getKey().endsWith(AUDIO_SUFFIX)) {
                audio = FileUtil.getInputStream(fileEntry.getValue());
                audio.skip(9);
            }
            if (video != null && audio != null) {
                mergeAudioAndVideo(countDownLatch, lad, executorService, video, audio, finalFile);
                return;
            }
        }
        countDownLatch.countDown();
        return;
    }

    private static void mergeAudioAndVideo(CountDownLatch countDownLatch, LongAdder lad, ExecutorService executorService, InputStream video, InputStream audio, File finalFile) {
//        mergeAudioAndVideo(video, audio, finalFile);
        CompletableFuture.runAsync(
                () -> {
                    try {
                        mergeAudioAndVideo(lad, video, audio, finalFile);
                    } catch (Exception e) {
                        new Exception("视频" + finalFile.getName() + "合并出错", e)
                                .printStackTrace(System.err);
                    } finally {
                        countDownLatch.countDown();
                    }
                }, executorService);
    }

    private static void mergeAudioAndVideo(LongAdder lad, InputStream video, InputStream audio, File finalFile) {
        File tmpDir = new File(finalFile.getParentFile(), "tmp");
        if (!tmpDir.exists()) {
            tmpDir.mkdirs();
        }
        File videoFile;
        File audioFile;
        try {
            videoFile = FileUtil.writeFromStream(video, new File(tmpDir, finalFile.getName() + ".video"));
            audioFile = FileUtil.writeFromStream(audio, new File(tmpDir, finalFile.getName() + ".audio"));
        } finally {
            try {
                video.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                audio.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        String ffmpegHome = System.getProperty(FFMPEG_HOME);
        try {
            if (!audioFile.exists()) {
                throw new IllegalStateException("文件复制出错");
            }
            RECORD_SET.add(finalFile.getName());
            ProcessBuilder processBuilder = new ProcessBuilder()
                    .directory(tmpDir)
                    .redirectErrorStream(true)
                    .command(
                            String.format("%s/bin/ffmpeg", ffmpegHome),
                            "-i", String.format("\"%s\"", videoFile.getName()),
                            "-i", String.format("\"%s\"", audioFile.getName()),
                            "-vcodec", "copy", "-acodec", "copy",
                            "../" + finalFile.getName());
            Process process = processBuilder.start();
            Thread successThread = new StreamThread(finalFile.getName(), process.getInputStream(), System.out);
            Thread errorThread = new StreamThread(finalFile.getName(), process.getErrorStream(), System.err);
            try {
                successThread.start();
                errorThread.start();

                int rat = process.waitFor();
                if (rat != 0) {
                    BufferedInputStream bufferedInputStream = new BufferedInputStream(process.getErrorStream());
                    byte[] bytes = new byte[1024];
                    int len;
                    while ((len = bufferedInputStream.read(bytes)) != -1) {
                        System.out.println(new String(bytes, 0, len));
                    }
                } else {
                    lad.add(finalFile.length());
                }
            } finally {
                successThread.interrupt();
                errorThread.interrupt();
            }
        } catch (Exception e) {
            System.err.println("视频" + finalFile.getName() + "合并失败");
            e.printStackTrace();
        } finally {
            RECORD_SET.remove(finalFile.getName());
            videoFile.delete();
            audioFile.delete();
            tmpDir.delete();
        }
    }

    private static boolean isWin() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static class StreamThread extends Thread {
        private InputStream inputStream;
        private PrintStream printStream;
        public StreamThread(String name, InputStream inputStream, PrintStream printStream) {
            this.inputStream = inputStream;
            this.printStream = printStream;
            this.setName(name);
            this.setUncaughtExceptionHandler((t, e) -> t.interrupt());
        }

        @Override
        public void run() {
            try {
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    printStream.println(this.getName() + ": " +line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }
}
