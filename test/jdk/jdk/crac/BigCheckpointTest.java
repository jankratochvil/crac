/*
 * Copyright (c) 2022, Azul Systems, Inc. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import jdk.crac.Core;
import jdk.test.lib.containers.docker.Common;
import jdk.test.lib.crac.CracBuilder;
import jdk.test.lib.crac.CracTest;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.StringTokenizer;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * @test
 * @library /test/lib
 * @build BigCheckpointTest
 * @run driver jdk.test.lib.crac.CracTest
 */
public class BigCheckpointTest implements CracTest {
    private static final String imageName = Common.imageName("big-checkpoint");

    @Override
    public void test() throws Exception {
        CracBuilder builder = new CracBuilder()
                .inDockerImage(imageName)
                .vmOption("-Xlog:gc,gc+heap,gc+heap+exit,gc+ergo+heap=debug")
                .vmOption("-XX:MinHeapFreeRatio=10").vmOption("-XX:MaxHeapFreeRatio=20");
        try {
            builder.doCheckpoint();
            builder.clearVmOptions();
            builder.doRestore();
        } finally {
            builder.ensureContainerKilled();
        }
    }

    @Override
    public void exec() throws Exception {
        var map = new HashMap<>();
        for (int i = 0; i < 10_000_000; ++i) {
            // at least partially compressible content
            map.put(createWord(), createWord());
        }
        deleteDirectory(new File("cr"));
        new File("cr.tar.gz").delete();
        long pid = ProcessHandle.current().pid();
        printMaps(pid);
        System.gc();
        printMaps(pid);
        System.out.println("Before checkpoint: " + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().toString());
        Core.checkpointRestore();
        long checkpointSize = Stream.of(new File("cr").listFiles()).mapToLong(f -> {
            try {
                return Files.size(f.toPath());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).sum();
        System.out.printf("Checkpoint size: %d MB%n", checkpointSize >> 20);
//        System.out.printf("Pages size before fallocate: ");
//        new ProcessBuilder().inheritIO().command("du", "-B", "1", "cr/pages-1.img").start().waitFor();
//        Runtime.getRuntime().exec("sudo fallocate -d cr/pages-1.img").waitFor();
//        System.out.printf("Pages size after fallocate -d: ");
//        new ProcessBuilder().inheritIO().command("du", "-B", "1", "cr/pages-1.img").start().waitFor();
//        long start = System.nanoTime();
//        Runtime.getRuntime().exec(new String[] { "bash", "-c", "tar czf - cr | gpg --cipher-algo aes256 --symmetric --batch --passphrase pa55w012d -o cr.tar.gz"}, null).waitFor();
//        long stop = System.nanoTime();
//        System.out.printf("Compressed size: %d MB%n", Files.size(Path.of("cr.tar.gz")) >> 20);
//        System.out.printf("Compression & encryption took %d ms.%n", TimeUnit.NANOSECONDS.toMillis(stop - start));
        System.out.println("After checkpoint: " + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().toString());
    }

    private static void printMaps(long pid) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(new ProcessBuilder()
                .command("cat", "/proc/" + pid + "/maps").start().getInputStream()))) {
            String line;
            Pattern p = Pattern.compile("([0-9a-f]+)-([0-9a-f]+) rw.*");
            while ((line = reader.readLine()) != null) {
                Matcher matcher = p.matcher(line);
                if (matcher.matches()) {
                    long from = Long.parseLong(matcher.group(1), 16);
                    long to = Long.parseLong(matcher.group(2), 16);
                    System.out.printf("%5d MB %s%n", (to - from) >> 20, line);
                } else {
                    System.out.printf("         %s%n", line);
                }
            }
        }
    }

    static boolean deleteDirectory(File dir) {
        File[] all = dir.listFiles();
        if (all != null) {
            for (File file : all) {
                deleteDirectory(file);
            }
        }
        return dir.delete();
    }

    static String createWord() {
        byte[] bytes = new byte[16];
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < bytes.length; ++i) {
            bytes[i] = (byte) random.nextInt('A', 'Z');
        }
        return new String(bytes);
    }
}
