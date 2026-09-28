package org.powernukkitx.wizard;

import org.powernukkitx.lang.BaseLang;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Minimal first-run setup wizard. Only asks two things: which language to use,
 * and whether the LGPL license is accepted - nothing else. Reads/writes plain
 * text over System.in/System.out directly (no JLine/terminal library), since
 * a two-question flow doesn't need fancy line editing, and doing it this way
 * avoids the whole class of terminal-provider/platform reliability problems
 * that come with negotiating a "real" interactive terminal.
 *
 * @author AzaleeX
 * @author xRookieFight
 *
 * @since 17/12/2025
 */
@Slf4j
public class SetupWizard implements AutoCloseable {
    private final BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
    private final Map<String, String> availableLanguages;
    private final WizardConfig wizardConfig = new WizardConfig();
    private final boolean interactive;
    private final boolean unicodeOutput;
    protected BaseLang baseLang;

    public SetupWizard() {
        this.interactive = System.console() != null && !SetupWizardSupport.isAutomatedEnvironment();
        this.unicodeOutput = supportsUnicodeOutput();
        this.availableLanguages = loadAvailableLanguages();
    }

    private Map<String, String> loadAvailableLanguages() {
        Map<String, String> languages = new LinkedHashMap<>();
        try (InputStream languageList = getClass().getClassLoader().getResourceAsStream("language/language.list")) {
            if (languageList == null) {
                throw new IllegalStateException("language/language.list is missing. If you are running a development version, make sure you have run 'git submodule update --init --recursive'.");
            }

            try (Scanner scanner = new Scanner(languageList)) {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine().trim();
                    if (line.isEmpty()) continue;

                    String[] parts = line.split("=>");
                    if (parts.length == 2) {
                        String code = parts[0].trim();
                        String name = parts[1].trim();
                        languages.put(code, name);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to load language list", e);
            // Fallback to English
            languages.put("eng", "English");
        }
        return languages;
    }

    /**
     * Runs the wizard: asks for a language, then requires the license to be
     * accepted before returning a usable config. If the license is not
     * accepted, {@link WizardConfig#isLicenseAccepted()} will be false on the
     * returned object, and the caller is expected to refuse to start the
     * server - this wizard does not proceed past that point.
     *
     * @param predefinedLanguage Optional predefined language from command line
     * @param forceAcceptLicense If true, automatically accept the license
     * @param serverName Optional server name (MOTD) from command line
     * @param port Optional server port from command line
     * @return The wizard configuration
     */
    public WizardConfig run(String predefinedLanguage, boolean forceAcceptLicense, String serverName, Integer port) {
        String selectedLanguage = selectLanguage(predefinedLanguage);
        wizardConfig.setLanguage(selectedLanguage);
        baseLang = new BaseLang(selectedLanguage);

        if (!acceptLicense(forceAcceptLicense)) {
            println();
            refuse(baseLang.tr("pnx.setupWizard.license.no_accept"));
            refuse(baseLang.tr("pnx.setupWizard.license.terminating"));
            return wizardConfig;
        }
        wizardConfig.setLicenseAccepted(true);

        // Server name/port may still be set via CLI args; everything else
        // (gamemode, max players, whitelist, operators, query, etc.) just
        // uses WizardConfig's own defaults - this wizard intentionally only
        // asks about language and the license, nothing more.
        if (serverName != null && !serverName.isEmpty()) {
            wizardConfig.setMotd(serverName);
        }
        if (port != null) {
            wizardConfig.setPort(port);
        }

        println();
        println(baseLang.tr("pnx.setupWizard.skipped"));

        return wizardConfig;
    }

    private String selectLanguage(String predefinedLanguage) {
        println();
        println(borderLine());
        println(centerText("Reactium Setup Wizard - Language Selection", 59));
        println(borderLine());
        println();
        println("Welcome! Please choose a language first!");
        println();

        if (predefinedLanguage != null && !predefinedLanguage.isEmpty()) {
            String normalizedLanguage = SetupWizardSupport.normalizeLanguageCode(predefinedLanguage);
            if (validateLanguage(normalizedLanguage)) {
                accept("Using predefined language: " + normalizedLanguage);
                return normalizedLanguage;
            } else {
                refuse("Invalid predefined language: " + predefinedLanguage);
                println("  Please choose a valid language from the list.");
            }
        }

        List<Map.Entry<String, String>> languageList = new ArrayList<>(availableLanguages.entrySet());
        String defaultLanguage = languageList.isEmpty() ? "eng" : languageList.get(0).getKey();
        if (!interactive) {
            accept("Automated environment detected. Language selected: " + defaultLanguage);
            println();
            return defaultLanguage;
        }

        notice("Enter a language code from the list below (press Enter for default).");
        for (Map.Entry<String, String> entry : languageList) {
            println("  [" + entry.getKey() + "] " + entry.getValue());
        }
        println();

        while (true) {
            print(promptText("Language code [" + defaultLanguage + "]: "));
            String line = SetupWizardSupport.normalizeLanguageCode(readLine());
            if (line.isEmpty()) {
                accept("Language selected: " + defaultLanguage + " (" + availableLanguages.get(defaultLanguage) + ")");
                println();
                return defaultLanguage;
            } else if (validateLanguage(line)) {
                accept("Language selected: " + line + " (" + availableLanguages.get(line) + ")");
                println();
                return line;
            } else {
                warn("Invalid input. Enter a valid language code from the list.");
            }
        }
    }

    /**
     * Displays the license and asks for acceptance. MANDATORY - startup must
     * not proceed if this returns false.
     *
     * @param forceAccept If true, accept the license automatically (e.g. via
     *                     the --accept-license CLI flag)
     * @return true if the license is accepted, false otherwise
     */
    public boolean acceptLicense(boolean forceAccept) {
        if (forceAccept) {
            println();
            println("License automatically accepted by command line argument.");
            return true;
        }
        if (!interactive) {
            // Deliberately fail closed here rather than auto-accepting: the
            // license is a legal requirement, not a default setting, so an
            // environment where we can't actually ask the question is not a
            // valid way to accept it. Use --accept-license if this is
            // intentional (e.g. an automated/headless deployment).
            println();
            refuse("Cannot prompt for license acceptance in a non-interactive environment.");
            refuse("Re-run interactively, or pass --accept-license if you accept the license.");
            return false;
        }
        println();
        println(borderLine());
        println("          GNU Lesser General Public License v3.0");
        println(borderLine());
        println();
        println("Reactium is licensed under the GNU LGPL v3.0 (based on PowerNukkitX)");
        println();
        println("This program is free software: you can redistribute it and/or modify");
        println("it under the terms of the GNU Lesser General Public License as published");
        println("by the Free Software Foundation, either version 3 of the License, or");
        println("(at your option) any later version.");
        println();
        println("This program is distributed in the hope that it will be useful,");
        println("but WITHOUT ANY WARRANTY; without even the implied warranty of");
        println("MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.");
        println();
        println("See the GNU Lesser General Public License for more details:");
        println("https://www.gnu.org/licenses/lgpl-3.0.html");
        println();
        warn(baseLang.tr("pnx.setupWizard.license.notice"));
        println();
        return askConfirmation(
            baseLang.tr("pnx.setupWizard.license.question"),
            baseLang.tr("pnx.setupWizard.license.accept"),
            baseLang.tr("pnx.setupWizard.license.no_accept"),
            baseLang.tr("pnx.setupWizard.invalid_input"),
            false
        );
    }

    private boolean askConfirmation(String prompt, String acceptMsg, String refuseMsg, String invalidMsg, boolean defaultAccept) {
        while (true) {
            print(promptText(prompt));
            String line = readLine().trim();
            if (line.isEmpty()) {
                if (defaultAccept) {
                    accept(acceptMsg);
                    return true;
                } else {
                    refuse(refuseMsg);
                    return false;
                }
            } else if (line.equalsIgnoreCase("y") || line.equalsIgnoreCase("yes")) {
                accept(acceptMsg);
                return true;
            } else if (line.equalsIgnoreCase("n") || line.equalsIgnoreCase("no")) {
                refuse(refuseMsg);
                return false;
            } else {
                warn(invalidMsg);
            }
        }
    }

    private boolean validateLanguage(String languageCode) {
        languageCode = SetupWizardSupport.normalizeLanguageCode(languageCode);
        if (languageCode.isEmpty()) {
            return false;
        }

        if (!SetupWizardSupport.isLanguageCodeFormat(languageCode)) {
            log.warn("Invalid language code format (must be 3 lowercase letters): {}", languageCode);
            return false;
        }

        if (availableLanguages.containsKey(languageCode)) {
            return true;
        }

        String resourcePath = String.format("language/%s/lang.json", languageCode);
        try (InputStream conf = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            return conf != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * No terminal-level resources are held by this implementation, so there's
     * nothing to release here. Notably, this deliberately does NOT close the
     * BufferedReader wrapping System.in - doing so would close System.in
     * itself, which would break the server's own console input afterward.
     */
    @Override
    public void close() {
    }

    public void setBaseLang(BaseLang lang) {
        this.baseLang = lang;
    }

    private String readLine() {
        try {
            String line = input.readLine();
            return line == null ? "" : line;
        } catch (IOException e) {
            log.error("Error reading input", e);
            return "";
        }
    }

    private void println() {
        System.out.println();
    }

    private void println(String message) {
        System.out.println(message);
    }

    private void print(String message) {
        System.out.print(message);
        System.out.flush();
    }

    private String centerText(String text, int width) {
        if (text == null) return "";
        int padSize = Math.max(0, width - text.length());
        int padStart = padSize / 2;
        int padEnd = padSize - padStart;
        return " ".repeat(padStart) + text + " ".repeat(padEnd);
    }

    private void warn(String message) {
        println("[!] " + message);
    }
    private void accept(String message) {
        println((unicodeOutput ? "✓ " : "[OK] ") + message);
    }
    private void refuse(String message) {
        println("[x] " + message);
    }
    private void notice(String message) {
        println("[*] " + message);
    }

    private String promptText(String text) {
        return promptPrefix() + text;
    }

    private String promptPrefix() {
        return unicodeOutput ? "» " : "> ";
    }

    private String borderLine() {
        return (unicodeOutput ? "═" : "=").repeat(59);
    }

    private static boolean supportsUnicodeOutput() {
        String encoding = System.getProperty("sun.stdout.encoding", Charset.defaultCharset().name());
        Charset charset;
        try {
            charset = Charset.forName(encoding);
        } catch (Exception e) {
            charset = Charset.defaultCharset();
        }
        return SetupWizardSupport.supportsUnicodeOutput(charset);
    }
}
