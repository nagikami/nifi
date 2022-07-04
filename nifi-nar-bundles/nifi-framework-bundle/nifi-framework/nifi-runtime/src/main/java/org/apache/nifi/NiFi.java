/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi;

import org.apache.nifi.bundle.Bundle;
import org.apache.nifi.diagnostics.DiagnosticsDump;
import org.apache.nifi.nar.ExtensionMapping;
import org.apache.nifi.nar.NarClassLoaders;
import org.apache.nifi.nar.NarClassLoadersHolder;
import org.apache.nifi.nar.NarUnpacker;
import org.apache.nifi.nar.SystemBundle;
import org.apache.nifi.processor.DataUnit;
import org.apache.nifi.util.DiagnosticUtils;
import org.apache.nifi.util.FileUtils;
import org.apache.nifi.util.NiFiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class NiFi implements NiFiEntryPoint {

    public static final String BOOTSTRAP_PORT_PROPERTY = "nifi.bootstrap.listen.port";
    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static final Logger LOGGER = LoggerFactory.getLogger(NiFi.class);
    private static final String KEY_FILE_FLAG = "-K";

    private final NiFiServer nifiServer;
    private final BootstrapListener bootstrapListener;
    private final NiFiProperties properties;

    private volatile boolean shutdown = false;

    public NiFi(final NiFiProperties properties)
            throws ClassNotFoundException, IOException, IllegalArgumentException {
        // 使用应用类加载器初始化NiFi
        this(properties, ClassLoader.getSystemClassLoader());
    }

    public NiFi(final NiFiProperties properties, ClassLoader rootClassLoader)
            throws ClassNotFoundException, IOException, IllegalArgumentException {

        this.properties = properties;

        // There can only be one krb5.conf for the overall Java process so set this globally during
        // start up so that processors and our Kerberos authentication code don't have to set this
        // 当前NiFi服务只有一份全局的krb5.conf
        final File kerberosConfigFile = properties.getKerberosConfigurationFile();
        if (kerberosConfigFile != null) {
            final String kerberosConfigFilePath = kerberosConfigFile.getAbsolutePath();
            LOGGER.debug("Setting java.security.krb5.conf to {}", kerberosConfigFilePath);
            System.setProperty("java.security.krb5.conf", kerberosConfigFilePath);
        }

        // 设置默认异常处理器
        setDefaultUncaughtExceptionHandler();

        // register the shutdown hook 注册虚拟机停止钩子
        addShutdownHook();

        final String bootstrapPort = System.getProperty(BOOTSTRAP_PORT_PROPERTY);
        if (bootstrapPort != null) {
            try {
                final int port = Integer.parseInt(bootstrapPort);

                if (port < 1 || port > 65535) {
                    throw new RuntimeException("Failed to start NiFi because system property '" + BOOTSTRAP_PORT_PROPERTY + "' is not a valid integer in the range 1 - 65535");
                }

                bootstrapListener = new BootstrapListener(this, port);
                // 使用NiFi启动时注释
                //bootstrapListener.start(properties.getDefaultListenerBootstrapPort());
            } catch (final NumberFormatException nfe) {
                throw new RuntimeException("Failed to start NiFi because system property '" + BOOTSTRAP_PORT_PROPERTY + "' is not a valid integer in the range 1 - 65535");
            }
        } else {
            LOGGER.info("NiFi started without Bootstrap Port information provided; will not listen for requests from Bootstrap");
            bootstrapListener = null;
        }

        // delete the web working dir - if the application does not start successfully
        // the web app directories might be in an invalid state. when this happens
        // jetty will not attempt to re-extract the war into the directory. by removing
        // the working directory, we can be assured that it will attempt to extract the
        // war every time the application starts.
        File webWorkingDir = properties.getWebWorkingDirectory();
        FileUtils.deleteFilesInDirectory(webWorkingDir, null, LOGGER, true, true);
        FileUtils.deleteFile(webWorkingDir, LOGGER, 3);

        // 检查时间问题
        detectTimingIssues();

        // redirect JUL log events
        initLogging();

        // 根据nifi.nar.library.directory（lib）和应用类加载器初始化bundle
        final Bundle systemBundle = SystemBundle.create(properties, rootClassLoader);

        // expand the nars 解压nar并返回lib下的jar包含的NiFi组件的信息映射（如果存在）
        final ExtensionMapping extensionMapping = NarUnpacker.unpackNars(properties, systemBundle);

        // load the extensions classloaders 加载nar类加载器
        NarClassLoaders narClassLoaders = NarClassLoadersHolder.getInstance();

        // 初始化nar类加载器
        narClassLoaders.init(rootClassLoader, properties.getFrameworkWorkingDirectory(), properties.getExtensionsWorkingDirectory(), true);

        // load the framework classloader 获取framework类加载器
        final ClassLoader frameworkClassLoader = narClassLoaders.getFrameworkBundle().getClassLoader();
        if (frameworkClassLoader == null) {
            throw new IllegalStateException("Unable to find the framework NAR ClassLoader.");
        }

        // 获取bundle信息和对应的类加载器
        final Set<Bundle> narBundles = narClassLoaders.getBundles();

        final long startTime = System.nanoTime();
        nifiServer = narClassLoaders.getServer();
        if (nifiServer == null) {
            throw new IllegalStateException("Unable to find a NiFiServer implementation.");
        }
        // 设置线程上下文加载器为NiFiServer的类加载器（NiFiServer对应bundle的nar类加载器）
        Thread.currentThread().setContextClassLoader(nifiServer.getClass().getClassLoader());
        // Filter out the framework NAR from being loaded by the NiFiServer
        nifiServer.initialize(properties,
                systemBundle,
                narBundles,
                extensionMapping);

        // 若被NiFi bootstrap发送shutdown命令则终止启动
        if (shutdown) {
            LOGGER.info("NiFi has been shutdown via NiFi Bootstrap. Will not start Controller");
        } else {
            // 启动jetty server
            nifiServer.start();

            if (bootstrapListener != null) {
                bootstrapListener.setNiFiLoaded(true);
                // 使用NiFi启动时注释
                //bootstrapListener.sendStartedStatus(true);
            }

            final long duration = System.nanoTime() - startTime;
            final double durationSeconds = TimeUnit.NANOSECONDS.toMillis(duration) / 1000.0;
            LOGGER.info("Started Application Controller in {} seconds ({} ns)", durationSeconds, duration);
        }
    }

    public NiFiServer getServer() {
        return nifiServer;
    }

    protected void setDefaultUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, exception) -> {
            LOGGER.error("An Unknown Error Occurred in Thread {}: {}", thread, exception.toString(), exception);
        });
    }

    protected void addShutdownHook() {
        /**
         * 添加虚拟机停止钩子
         * 当jvm正常退出或者程序调用exit方法时，将按照非确定顺序调用并发调用注册的hook方法
         * 当使用exit退出时，守护线程和非守护线程会和hook方法同时运行
         */
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                // shutdown the jetty server
                shutdownHook(false)
        ));
    }

    protected void initLogging() {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    private static ClassLoader createBootstrapClassLoader() {
        //Get list of files in bootstrap folder
        final List<URL> urls = new ArrayList<>();
        // 遍历lib/bootstrap下的jar
        try (final Stream<Path> files = Files.list(Paths.get("lib/bootstrap"))) {
            files.forEach(p -> {
                try {
                    urls.add(p.toUri().toURL());
                } catch (final MalformedURLException mef) {
                    LOGGER.warn("Unable to load bootstrap library [{}]", p.getFileName(), mef);
                }
            });
        } catch (IOException ioe) {
            LOGGER.warn("Unable to access lib/bootstrap to create bootstrap classloader", ioe);
        }
        //Create the bootstrap classloader 自定义NiFi启动类加载器加载lib/bootstrap下的类，设置父加载器为线程上下文类加载器
        return new URLClassLoader(urls.toArray(new URL[0]), Thread.currentThread().getContextClassLoader());
    }

    public void shutdownHook(final boolean isReload) {
        try {
            // 停止时记录诊断信息
            runDiagnosticsOnShutdown();
            // 停止jetty服务
            shutdown();
        } catch (final Throwable t) {
            LOGGER.warn("Problem occurred ensuring Jetty web server was properly terminated", t);
        }
    }

    private void runDiagnosticsOnShutdown() throws IOException {
        if (properties.isDiagnosticsOnShutdownEnabled()) {
            final String diagnosticDirectoryPath = properties.getDiagnosticsOnShutdownDirectory();
            final boolean isCreated = DiagnosticUtils.createDiagnosticDirectory(diagnosticDirectoryPath);
            if (isCreated) {
                LOGGER.debug("Diagnostic directory has successfully been created.");
            }
            // 保证诊断文件合法性
            while (DiagnosticUtils.isFileCountExceeded(diagnosticDirectoryPath, properties.getDiagnosticsOnShutdownMaxFileCount())
                    || DiagnosticUtils.isSizeExceeded(diagnosticDirectoryPath, DataUnit.parseDataSize(properties.getDiagnosticsOnShutdownDirectoryMaxSize(), DataUnit.B).longValue())) {
                final Path oldestFile = DiagnosticUtils.getOldestFile(diagnosticDirectoryPath);
                Files.delete(oldestFile);
            }
            final String fileName = String.format("%s/diagnostic-%s.log", diagnosticDirectoryPath, DATE_TIME_FORMATTER.format(LocalDateTime.now()));
            diagnose(new File(fileName), properties.isDiagnosticsOnShutdownVerbose());
        }
    }

    private void diagnose(final File file, final boolean verbose) throws IOException {
        final DiagnosticsDump diagnosticsDump = getServer().getDiagnosticsFactory().create(verbose);
        try (final OutputStream fileOutputStream = new FileOutputStream(file)) {
            diagnosticsDump.writeTo(fileOutputStream);
        }
    }


    protected void shutdown() {
        this.shutdown = true;

        LOGGER.info("Application Server shutdown started");
        // 停止NiFi jetty server
        if (nifiServer != null) {
            nifiServer.stop();
        }
        // 停止bootstrapListener监听服务
        if (bootstrapListener != null) {
            bootstrapListener.stop();
        }
        LOGGER.info("Application Server shutdown completed");
    }

    /**
     * Determine if the machine we're running on has timing issues.
     */
    private void detectTimingIssues() {
        final int minRequiredOccurrences = 25;
        final int maxOccurrencesOutOfRange = 15;
        final AtomicLong lastTriggerMillis = new AtomicLong(System.currentTimeMillis());

        final ScheduledExecutorService service = Executors.newScheduledThreadPool(1, new ThreadFactory() {
            private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();

            @Override
            public Thread newThread(final Runnable runnable) {
                final Thread t = defaultFactory.newThread(runnable);
                t.setDaemon(true);
                t.setName("Detect Timing Issues");
                return t;
            }
        });

        final AtomicInteger occurrencesOutOfRange = new AtomicInteger(0);
        final AtomicInteger occurrences = new AtomicInteger(0);
        final Runnable command = () -> {
            final long curMillis = System.currentTimeMillis();
            // 计算时间间隔
            final long difference = curMillis - lastTriggerMillis.get();
            final long millisOff = Math.abs(difference - 2000L);
            occurrences.incrementAndGet();
            // 时间间隔超时，则记录
            if (millisOff > 500L) {
                occurrencesOutOfRange.incrementAndGet();
            }
            lastTriggerMillis.set(curMillis);
        };

        // service调度
        final ScheduledFuture<?> future = service.scheduleWithFixedDelay(command, 2000L, 2000L, TimeUnit.MILLISECONDS);

        final TimerTask timerTask = new TimerTask() {
            @Override
            public void run() {
                future.cancel(true);
                service.shutdownNow();

                // 在时间范围内，调用次数小于应调用次数，偏差次数大于应偏差次数，则时间异常
                if (occurrences.get() < minRequiredOccurrences || occurrencesOutOfRange.get() > maxOccurrencesOutOfRange) {
                    LOGGER.warn("NiFi has detected that this box is not responding within the expected timing interval, which may cause "
                            + "Processors to be scheduled erratically. Please see the NiFi documentation for more information.");
                }
            }
        };
        final Timer timer = new Timer(true);
        // 一分钟后检查service的调度结果
        timer.schedule(timerTask, 60000L);
    }

    /**
     * Main entry point of the application.
     * 应用主入口
     * @param args things which are ignored
     */
    public static void main(String[] args) {
        LOGGER.info("Launching NiFi...");
        try {
            // 加载属性
            NiFiProperties properties = convertArgumentsToValidatedNiFiProperties(args);
            new NiFi(properties);
        } catch (final Throwable t) {
            LOGGER.error("Failure to launch NiFi", t);
        }
    }

    protected static NiFiProperties convertArgumentsToValidatedNiFiProperties(String[] args) {
        return convertArgumentsToValidatedNiFiProperties(args, createBootstrapClassLoader());
    }

    protected static NiFiProperties convertArgumentsToValidatedNiFiProperties(String[] args, final ClassLoader bootstrapClassLoader) {
        NiFiProperties properties = initializeProperties(args, bootstrapClassLoader);
        properties.validate();
        return properties;
    }

    private static NiFiProperties initializeProperties(final String[] args, final ClassLoader bootstrapLoader) {
        // Try to get key
        // If key doesn't exist, instantiate without
        // Load properties
        // If properties are protected and key missing, throw RuntimeException
        // 获取线程上下文类加载器（应用类加载器）

        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        final String key;
        try {
            key = loadFormattedKey(args);
            // The key might be empty or null when it is passed to the loader
        } catch (IllegalArgumentException e) {
            final String msg = "The bootstrap process did not provide a valid key";
            throw new IllegalArgumentException(msg, e);
        }
        // 设置线程类加载器为NiFi启动类加载器（父类加载器为应用类加载器）
        Thread.currentThread().setContextClassLoader(bootstrapLoader);

        try {
            // 使用NiFi启动类加载器加载属性加载器，bootstrapLoader为初始类加载器（触发类加载），AppClassLoader为定义类加载器（实际加载类）
            final Class<?> propsLoaderClass = Class.forName("org.apache.nifi.properties.NiFiPropertiesLoader", true, bootstrapLoader);
            final Method withKeyMethod = propsLoaderClass.getMethod("withKey", String.class);
            // 使用反射调用静态方法返回属性加载器实例
            final Object loaderInstance = withKeyMethod.invoke(null, key);
            final Method getMethod = propsLoaderClass.getMethod("get");
            final NiFiProperties properties = (NiFiProperties) getMethod.invoke(loaderInstance);
            LOGGER.info("Application Properties loaded [{}]", properties.size());
            return properties;
        } catch (InvocationTargetException wrappedException) {
            final String msg = "There was an issue decrypting protected properties";
            throw new IllegalArgumentException(msg, wrappedException.getCause() == null ? wrappedException : wrappedException.getCause());
        } catch (final IllegalAccessException | NoSuchMethodException | ClassNotFoundException reex) {
            final String msg = "Unable to access properties loader in the expected manner - apparent classpath or build issue";
            throw new IllegalArgumentException(msg, reex);
        } catch (final RuntimeException e) {
            final String msg = "There was an issue decrypting protected properties";
            throw new IllegalArgumentException(msg, e);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    private static String loadFormattedKey(String[] args) {
        String key = null;
        List<String> parsedArgs = parseArgs(args);
        // Check if args contain protection key
        if (parsedArgs.contains(KEY_FILE_FLAG)) {
            key = getKeyFromKeyFileAndPrune(parsedArgs);
            // Format the key (check hex validity and remove spaces)
            key = formatHexKey(key);

        }

        if (null == key) {
            return "";
        } else if (!isHexKeyValid(key)) {
            throw new IllegalArgumentException("The key was not provided in valid hex format and of the correct length");
        } else {
            return key;
        }
    }

    private static String getKeyFromKeyFileAndPrune(List<String> parsedArgs) {
        String key = null;
        LOGGER.debug("The bootstrap process provided the {} flag", KEY_FILE_FLAG);
        int i = parsedArgs.indexOf(KEY_FILE_FLAG);
        if (parsedArgs.size() <= i + 1) {
            LOGGER.error("The bootstrap process passed the {} flag without a filename", KEY_FILE_FLAG);
            throw new IllegalArgumentException("The bootstrap process provided the " + KEY_FILE_FLAG + " flag but no key");
        }
        try {
            String passwordFilePath = parsedArgs.get(i + 1);
            // Slurp in the contents of the file:
            byte[] encoded = Files.readAllBytes(Paths.get(passwordFilePath));
            key = new String(encoded, StandardCharsets.UTF_8);
            if (0 == key.length())
                throw new IllegalArgumentException("Key in keyfile " + passwordFilePath + " yielded an empty key");

            LOGGER.debug("Overwriting temporary bootstrap key file [{}]", passwordFilePath);

            // Overwrite the contents of the file (to avoid littering file system
            // unlinked with key material):
            File passwordFile = new File(passwordFilePath);
            FileWriter overwriter = new FileWriter(passwordFile, false);

            // Construe a random pad:
            Random random = new Random();
            StringBuffer sb = new StringBuffer();
            // Note on correctness: this pad is longer, but equally sufficient.
            while (sb.length() < encoded.length) {
                sb.append(Integer.toHexString(random.nextInt()));
            }
            String pad = sb.toString();
            overwriter.write(pad);
            overwriter.close();

            LOGGER.debug("Removing temporary bootstrap key file [{}]", passwordFilePath);
            passwordFile.delete();

        } catch (IOException e) {
            LOGGER.error("Caught IOException while retrieving the {} -passed keyfile; aborting: {}", KEY_FILE_FLAG, e.toString());
            System.exit(1);
        }

        return key;
    }

    private static List<String> parseArgs(String[] args) {
        List<String> parsedArgs = new ArrayList<>(Arrays.asList(args));
        for (int i = 0; i < parsedArgs.size(); i++) {
            if (parsedArgs.get(i).startsWith(KEY_FILE_FLAG + " ")) {
                String[] split = parsedArgs.get(i).split(" ", 2);
                parsedArgs.set(i, split[0]);
                parsedArgs.add(i + 1, split[1]);
                break;
            }
        }
        return parsedArgs;
    }

    private static String formatHexKey(String input) {
        if (input == null || input.trim().isEmpty()) {
            return "";
        }
        return input.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
    }

    private static boolean isHexKeyValid(String key) {
        if (key == null || key.trim().isEmpty()) {
            return false;
        }
        // Key length is in "nibbles" (i.e. one hex char = 4 bits)
        return Arrays.asList(128, 196, 256).contains(key.length() * 4) && key.matches("^[0-9a-fA-F]*$");
    }
}
