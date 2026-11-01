package pl.fuzjajadrowa.ingot.conversion;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class ConversionJournalTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void journalPersistsAllowedPhasesAndRejectsSkippingVerification() throws Exception {
        Path root = temporary.newFolder("journal").toPath();
        ConversionJournal journal = ConversionJournal.create(root, ConversionDirection.ANVIL_TO_INGOT);
        assertEquals(ConversionJournal.Phase.PREFLIGHT, ConversionJournal.read(root).phase());
        try {
            journal.advance(root, ConversionJournal.Phase.PUBLICATION, "skip");
            fail("Publication cannot be reached without staging and verification");
        } catch (java.io.IOException expected) { }
        journal = journal.advance(root, ConversionJournal.Phase.STAGING, "staging");
        journal = journal.advance(root, ConversionJournal.Phase.VERIFICATION, "verified staged output");
        journal = journal.advance(root, ConversionJournal.Phase.PUBLICATION, "publish");
        journal = journal.advance(root, ConversionJournal.Phase.COMPLETE, "complete");
        assertEquals(journal, ConversionJournal.read(root));
        assertTrue(Files.exists(root.resolve(ConversionJournal.FILE_NAME)));
        journal.clearAfterSuccessfulCompletion(root);
        assertFalse(Files.exists(root.resolve(ConversionJournal.FILE_NAME)));
    }

    @Test public void checksumRejectsModifiedJournal() throws Exception {
        Path root = temporary.newFolder("checksum").toPath();
        ConversionJournal.create(root, ConversionDirection.INGOT_TO_ANVIL);
        Path file = root.resolve(ConversionJournal.FILE_NAME);
        Files.writeString(file, Files.readString(file).replace("phase=PREFLIGHT", "phase=PUBLICATION"));
        try {
            ConversionJournal.read(root);
            fail("Modified journal must not be trusted");
        } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("checksum")); }
    }

    @Test public void lockIsExclusiveAndReleasedOnClose() throws Exception {
        Path root = temporary.newFolder("lock").toPath();
        try (WorldConversionLock ignored = WorldConversionLock.acquire(root)) {
            try {
                WorldConversionLock.acquire(root);
                fail("A second converter must not acquire the same world");
            } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("already being converted")); }
        }
        try (WorldConversionLock ignored = WorldConversionLock.acquire(root)) { }
    }
}