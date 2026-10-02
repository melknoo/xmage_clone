package dev.magelite.boot;

import org.apache.log4j.PropertyConfigurator;

import java.nio.file.Path;
import java.util.Properties;

/**
 * Log4j-Konfiguration (XMage nutzt log4j 1.x / reload4j, Javalin via slf4j-reload4j).
 */
public final class LogConfig {

    private LogConfig() {
    }

    public static void configure(Path logDir, boolean verbose) {
        Properties p = new Properties();
        p.setProperty("log4j.rootLogger", "INFO, console, file");

        p.setProperty("log4j.appender.console", "org.apache.log4j.ConsoleAppender");
        p.setProperty("log4j.appender.console.Threshold", verbose ? "INFO" : "WARN");
        p.setProperty("log4j.appender.console.layout", "org.apache.log4j.PatternLayout");
        p.setProperty("log4j.appender.console.layout.ConversionPattern", "%d{HH:mm:ss,SSS} %-5p [%t] %c{1} - %m%n");

        p.setProperty("log4j.appender.file", "org.apache.log4j.RollingFileAppender");
        p.setProperty("log4j.appender.file.File", logDir.resolve("engine.log").toString());
        p.setProperty("log4j.appender.file.MaxFileSize", "10MB");
        p.setProperty("log4j.appender.file.MaxBackupIndex", "3");
        p.setProperty("log4j.appender.file.Encoding", "UTF-8");
        p.setProperty("log4j.appender.file.layout", "org.apache.log4j.PatternLayout");
        p.setProperty("log4j.appender.file.layout.ConversionPattern", "%d{yyyy-MM-dd HH:mm:ss,SSS} %-5p [%t] %c{1} - %m%n");

        // AI loggt auf INFO sehr viel (printBattlefieldScore kostet Zeit)
        p.setProperty("log4j.logger.mage.player.ai", "WARN");
        p.setProperty("log4j.logger.com.j256.ormlite", "WARN");
        p.setProperty("log4j.logger.org.eclipse.jetty", "WARN");
        p.setProperty("log4j.logger.io.javalin", "WARN");
        p.setProperty("log4j.logger.dev.magelite", "INFO");

        PropertyConfigurator.configure(p);
    }
}
