package top.code2life.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.StreamSupport;

/**
 * Enhance PropertySource when spring.config.location is specified, it will start directory-watch,
 * listening any changes on configuration files, then publish ConfigurationChangedEvent.
 *
 * @author Code2Life
 * @see ConfigurationChangedEvent
 */
@Slf4j
@Configuration
@ConditionalOnProperty("spring.config.location")
public class DynamicConfigPropertiesWatcher implements DisposableBean {

    private static final String FILE_COLON_SYMBOL = "file:";
    private static final String FILE_SOURCE_CONFIGURATION_PATTERN = "^.*Config\\sresource.*file.*$";
    private static final String FILE_SOURCE_CONFIGURATION_PATTERN_LEGACY = "^.+Config:\\s\\[file:.*$";
    private static final Map<String, PropertySourceMeta> PROPERTY_SOURCE_META_MAP = new HashMap<>(8);

    private final StandardEnvironment env;
    private final ApplicationEventPublisher eventPublisher;
    private final List<PropertySourceLoader> propertyLoaders;

    private WatchService watchService;

    @Value("${spring.config.location:}")
    private String configLocation;

    public DynamicConfigPropertiesWatcher(StandardEnvironment env, ApplicationEventPublisher eventPublisher) {
        this.env = env;
        this.eventPublisher = eventPublisher;
        this.propertyLoaders = SpringFactoriesLoader.loadFactories(PropertySourceLoader.class,
                getClass().getClassLoader());
    }

    @Override
    public void destroy() {
        closeConfigDirectoryWatch();
    }

    @PostConstruct
    @SuppressWarnings("AlibabaThreadPoolCreation")
    private void watchConfigDirectory() {
        if (!StringUtils.hasText(configLocation)) {
            log.info("no spring.config.location configured, file watch will not start.");
            return;
        }
        MutablePropertySources propertySources = env.getPropertySources();
        StreamSupport.stream(propertySources.spliterator(), false)
                .filter(ps -> ps.getName().matches(FILE_SOURCE_CONFIGURATION_PATTERN) || ps.getName().matches(FILE_SOURCE_CONFIGURATION_PATTERN_LEGACY))
                .forEach(ps -> {
                    String name = ps.getName();
                    int beginIndex = name.indexOf("[") + 1;
                    int endIndex = name.indexOf("]");
                    if (beginIndex < 1 && endIndex < 1) {
                        log.warn("unrecognized config location, property source name is: {}", name);
                    }
                    String pathStr = name.substring(beginIndex, endIndex);
                    if (pathStr.contains(FILE_COLON_SYMBOL)) {
                        pathStr = pathStr.replace(FILE_COLON_SYMBOL, "");
                    }
                    PROPERTY_SOURCE_META_MAP.put(pathStr.replaceAll("\\\\", "/"), new PropertySourceMeta(ps, Paths.get(pathStr), 0L));
                    if (log.isDebugEnabled()) {
                        log.debug("configuration file found: {}", pathStr);
                    }
                });
        Executors.newSingleThreadExecutor(r -> new Thread(r, "config-watcher")).submit(this::startWatchDir);
    }

    private void startWatchDir() {
        try {
            log.info("start watching configuration directory: {}", configLocation);
            watchService = FileSystems.getDefault().newWatchService();
            Paths.get(configLocation).register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
            WatchKey key;
            while ((key = watchService.take()) != null) {
                // avoid receiving two ENTRY_MODIFY events: file modified and timestamp updated
                Thread.sleep(50);
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path path = (Path) event.context();
                    reloadChangedFile(path);
                }
                key.reset();
            }
            log.warn("config directory watch stopped unexpectedly, dynamic configuration won't take effect.");
        } catch (ClosedWatchServiceException cse) {
            log.info("configuration watcher has been stopped.");
        } catch (Exception ex) {
            log.error("failed to watch config directory: ", ex);
        }
    }

    private void reloadChangedFile(Path path) {
        Path absPath = null;
        try {
            String extension = "";
            int i = path.toString().lastIndexOf('.');
            if (i > 0) {
                extension = path.toString().substring(i + 1);
            }
            absPath = Paths.get(configLocation, path.toString());
            String absPathStr = absPath.toString();
            // remove ./ or .\ at the beginning of the path
            boolean beginWithRelative = absPathStr.length() > 2 && (absPathStr.startsWith("./") || absPathStr.startsWith(".\\"));
            if (beginWithRelative) {
                absPathStr = absPathStr.substring(2);
            }
            PropertySourceMeta propertySourceMeta = PROPERTY_SOURCE_META_MAP.get(absPathStr.replaceAll("\\\\", "/"));
            if (propertySourceMeta == null) {
                log.warn("changed file at config location is not recognized: {}", absPathStr);
                return;
            }
            long currentModTs = Files.getLastModifiedTime(absPath).toMillis();
            long mdt = propertySourceMeta.getLastModifyTime();
            if (mdt != currentModTs) {
                log.info("config file has been changed: {}", path);
                String propertySourceName = propertySourceMeta.getPropertySource().getName();
                ConfigurationChangedEvent event = new ConfigurationChangedEvent(absPathStr);
                FileSystemResource resource = new FileSystemResource(absPath.toString());
                for (PropertySourceLoader loader : propertyLoaders) {
                    if (Arrays.asList(loader.getFileExtensions()).contains(extension)) {
                        // use this loader to load config resource
                        List<PropertySource<?>> newPropsList = loader.load(propertySourceName, resource);
                        if (newPropsList.size() >= 1) {
                            PropertySource<?> newProps = newPropsList.get(0);
                            event.setPrevious(env.getPropertySources().get(propertySourceName));
                            event.setCurrent(newProps);
                            env.getPropertySources().replace(propertySourceName, newProps);
                            propertySourceMeta.setLastModifyTime(currentModTs);
                            eventPublisher.publishEvent(event);
                        }
                        break;
                    }
                }
            }
        } catch (Exception ex) {
            log.error("reload configuration file {} failed: ", absPath, ex);
        }
    }

    private void closeConfigDirectoryWatch() {
        if (watchService != null) {
            try {
                watchService.close();
                log.info("config properties watcher bean is destroying, WatchService stopped.");
            } catch (IOException e) {
                log.warn("can not close config directory watcher. ", e);
            }
        }
    }

    @Data
    @AllArgsConstructor
    private static class PropertySourceMeta {

        private PropertySource<?> propertySource;

        private Path filePath;

        private long lastModifyTime;

    }
}