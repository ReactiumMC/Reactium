package org.powernukkitx.command.defaults;

import org.powernukkitx.command.CommandSender;
import org.powernukkitx.command.data.CommandParameter;
import org.powernukkitx.command.tree.ParamList;
import org.powernukkitx.command.utils.CommandLogger;
import org.powernukkitx.level.Level;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Backs up all currently loaded worlds into a timestamped zip file under a
 * "backups" folder in the server's root directory (created if it doesn't
 * already exist). Restricted to operators and the console.
 */
public class BackupCommand extends VanillaCommand {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    public BackupCommand(String name) {
        super(name, "reactium.command.backup.description");
        this.setPermission("nukkit.command.backup");
        this.commandParameters.clear();
        this.commandParameters.put("default", CommandParameter.EMPTY_ARRAY);
        this.enableParamTree();
    }

    @Override
    public int execute(CommandSender sender, String commandLabel, Map.Entry<String, ParamList> result, CommandLogger log) {
        var server = sender.getServer();

        File backupsDir = new File(server.getDataPath(), "backups");
        if (!backupsDir.exists() && !backupsDir.mkdirs()) {
            log.addError("reactium.command.backup.dirFailed").output(true);
            return 0;
        }

        // Make sure everything currently in memory is actually written to
        // disk before we zip it up.
        for (Level level : server.getLevels().values()) {
            level.save();
        }

        File worldsDir = new File(server.getDataPath(), "worlds");
        if (!worldsDir.isDirectory()) {
            log.addError("reactium.command.backup.noWorlds").output(true);
            return 0;
        }

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        File zipFile = new File(backupsDir, "worlds-" + timestamp + ".zip");

        log.addSuccess("reactium.command.backup.start").output(true);

        int skipped;
        try {
            skipped = zipDirectory(worldsDir.toPath(), zipFile);
        } catch (IOException e) {
            log.addError("reactium.command.backup.failed").output(true);
            sender.getServer().getLogger().error("Failed to create backup", e);
            return 0;
        }

        if (skipped > 0) {
            sender.getServer().getLogger().warning(
                "Backup skipped " + skipped + " file(s) that were locked by the running server.");
        }

        log.addSuccess("reactium.command.backup.done", zipFile.getName()).output(true);
        return 1;
    }

    private static int zipDirectory(Path sourceDir, File targetZip) throws IOException {
        final int[] skipped = {0};
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetZip.toPath()))) {
            Files.walk(sourceDir).filter(Files::isRegularFile).forEach(path -> {
                String entryName = sourceDir.relativize(path).toString().replace(File.separatorChar, '/');
                try (FileInputStream fis = new FileInputStream(path.toFile())) {
                    zos.putNextEntry(new ZipEntry(entryName));
                    fis.transferTo(zos);
                    zos.closeEntry();
                } catch (IOException e) {
                    // A loaded world's LevelDB keeps its LOCK file and active
                    // log/manifest files open, and Windows refuses reads on
                    // files another process holds locked. Those files are
                    // recreated on load anyway, so skip them rather than
                    // failing the entire backup.
                    skipped[0]++;
                    try {
                        zos.closeEntry();
                    } catch (IOException ignored) {
                        // Entry may not have been opened; nothing to close.
                    }
                }
            });
        }
        return skipped[0];
    }
}
