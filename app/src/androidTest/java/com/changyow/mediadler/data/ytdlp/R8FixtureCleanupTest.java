package com.changyow.mediadler.data.ytdlp;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Removes only UUID-named files owned by the R8 fixtures, including failed attempts. */
@RunWith(AndroidJUnit4.class)
public class R8FixtureCleanupTest {
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Test public void clearsOnlyOwnedR8Fixtures() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File[] entries = context.getCacheDir().listFiles();
        assertNotNull(entries);
        for (File entry : entries) {
            if (entry.getName().matches("r8-(ocr|native)-" + UUID_PATTERN)) deleteTree(entry);
        }
        for (String name : context.databaseList()) {
            if (name.matches("r8-kotlin-" + UUID_PATTERN + "-download_queue\\.db")) {
                assertTrue("Cannot delete owned fixture database: " + name, context.deleteDatabase(name));
            }
        }
        for (File entry : context.getCacheDir().listFiles()) {
            assertTrue("Owned cache fixture remains: " + entry,
                !entry.getName().matches("r8-(ocr|native)-" + UUID_PATTERN));
        }
        for (String name : context.databaseList()) {
            assertTrue("Owned database fixture remains: " + name,
                !name.matches("r8-kotlin-" + UUID_PATTERN + "-download_queue\\.db"));
        }
    }

    private static void deleteTree(File file) {
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            assertNotNull(children);
            for (File child : children) deleteTree(child);
        }
        assertTrue("Cannot delete owned fixture: " + file, file.delete());
    }
}
