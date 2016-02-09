package org.rmatil.sync.test.messaging.sharingexchange.share;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.rmatil.sync.core.config.Config;
import org.rmatil.sync.core.eventbus.IBusEvent;
import org.rmatil.sync.core.eventbus.IgnoreObjectStoreUpdateBusEvent;
import org.rmatil.sync.core.messaging.sharingexchange.share.ShareExchangeHandler;
import org.rmatil.sync.core.messaging.sharingexchange.share.ShareExchangeHandlerResult;
import org.rmatil.sync.core.messaging.sharingexchange.share.ShareRequest;
import org.rmatil.sync.core.messaging.sharingexchange.share.ShareRequestHandler;
import org.rmatil.sync.core.model.RemoteClientLocation;
import org.rmatil.sync.core.security.AccessManager;
import org.rmatil.sync.event.aggregator.core.events.CreateEvent;
import org.rmatil.sync.network.core.model.ClientLocation;
import org.rmatil.sync.persistence.exceptions.InputOutputException;
import org.rmatil.sync.test.messaging.base.BaseNetworkHandlerTest;
import org.rmatil.sync.version.api.AccessType;
import org.rmatil.sync.version.core.model.PathObject;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class ShareExchangeHandlerTest extends BaseNetworkHandlerTest {

    protected static Path   TEST_DIR_1       = Paths.get("testDir1");
    protected static Path   TEST_DIR_2       = Paths.get("testDir2");
    protected static Path   TEST_FILE_1      = TEST_DIR_1.resolve("myFile.txt");
    protected static Path   TEST_UNIQUE_FILE = TEST_DIR_1.resolve("myUniqueFile.txt");
    protected static Path   TEST_UNIQUE_DIR  = TEST_DIR_1.resolve("myUniqueDir");
    protected static byte[] content          = new byte[(ShareExchangeHandler.CHUNK_SIZE * 2) + 15]; // 1024*1024*10 - 21
    protected static UUID   EXCHANGE_ID      = UUID.randomUUID();
    protected static UUID   FILE_ID          = UUID.randomUUID();

    @BeforeClass
    public static void setUpChild()
            throws IOException, InterruptedException, InputOutputException {
        CLIENT_2.shutdown();

        // wait a bit until client2 has correctly shutdown
        Thread.sleep(1000L);

        CLIENT_2 = createClient(USER_2, STORAGE_ADAPTER_2, OBJECT_STORE_2, GLOBAL_EVENT_BUS_2, PORT_CLIENT_2, new RemoteClientLocation(
                CLIENT_1.getPeerAddress().inetAddress().getHostName(),
                CLIENT_1.getPeerAddress().isIPv6(),
                CLIENT_1.getPeerAddress().tcpPort()
        ));

        CLIENT_MANAGER_2 = CLIENT_2.getClientManager();
    }

    @Before
    public void before()
            throws IOException, InputOutputException, InterruptedException {

        deleteTestDirs();

        createTestDirs();
        createObjectStoreDirs();

        Files.createDirectory(ROOT_TEST_DIR1.resolve(TEST_DIR_1));
        Files.createDirectory(ROOT_TEST_DIR2.resolve(TEST_DIR_1));

        // only create files on first client
        Files.createDirectory(ROOT_TEST_DIR1.resolve(TEST_DIR_2));
        Files.createFile(ROOT_TEST_DIR1.resolve(TEST_FILE_1));

        OBJECT_STORE_1.getObjectManager().clear();
        OBJECT_STORE_1.sync(ROOT_TEST_DIR1.toFile());
        OBJECT_STORE_2.getObjectManager().clear();
        OBJECT_STORE_2.sync(ROOT_TEST_DIR2.toFile());

        RandomAccessFile randomAccessFile = new RandomAccessFile(ROOT_TEST_DIR1.resolve(TEST_FILE_1).toString(), "rw");
        randomAccessFile.write(content);
        randomAccessFile.close();

        String filePath = CLIENT_1.getIdentifierManager().getKey(FILE_ID);
        CLIENT_1.getIdentifierManager().removeIdentifier(filePath);

        String filePath2 = CLIENT_2.getIdentifierManager().getKey(FILE_ID);
        CLIENT_2.getIdentifierManager().removeIdentifier(filePath2);

        assertNull("FileId should not be present", CLIENT_1.getIdentifierManager().getKey(FILE_ID));
        assertNull("FileId should not be present", CLIENT_2.getIdentifierManager().getKey(FILE_ID));

        Thread.sleep(500L);
    }

    @Test
    public void testSendFile()
            throws InterruptedException, IOException, InputOutputException {
        EVENT_BUS_LISTENER_2.clear();

        ShareExchangeHandler shareExchangeHandler = new ShareExchangeHandler(
                CLIENT_1,
                new ClientLocation(CLIENT_2.getClientDeviceId(), CLIENT_2.getPeerAddress()),
                STORAGE_ADAPTER_1,
                OBJECT_STORE_1,
                TEST_FILE_1.toString(),
                TEST_FILE_1.getFileName().toString(),
                AccessType.WRITE,
                FILE_ID,
                true,
                EXCHANGE_ID
        );

        CLIENT_1.getObjectDataReplyHandler().addResponseCallbackHandler(EXCHANGE_ID, shareExchangeHandler);

        Thread fileShareExchangeHandlerThread = new Thread(shareExchangeHandler);
        fileShareExchangeHandlerThread.setName("TEST-ShareExchangeHandler");
        fileShareExchangeHandlerThread.start();

        // use a max of 30000 milliseconds to wait
        shareExchangeHandler.await();

        CLIENT_1.getObjectDataReplyHandler().removeResponseCallbackHandler(EXCHANGE_ID);

        assertTrue("ShareExchangeHandler should be completed after awaiting", shareExchangeHandler.isCompleted());

        ShareExchangeHandlerResult shareExchangeHandlerResult = shareExchangeHandler.getResult();

        assertNotNull("Result should not be null", shareExchangeHandlerResult);

        // check that shared folders are created
        assertTrue("SharedWithOthers (READ) should exist", Files.exists(ROOT_TEST_DIR2.resolve(Config.DEFAULT.getSharedWithOthersReadOnlyFolderName())));
        assertTrue("SharedWithOthers (READ-WRITE) should exist", Files.exists(ROOT_TEST_DIR2.resolve(Config.DEFAULT.getSharedWithOthersReadWriteFolderName())));

        assertTrue("File should exist in READ-WRITE folder", Files.exists(ROOT_TEST_DIR2.resolve(Config.DEFAULT.getSharedWithOthersReadWriteFolderName()).resolve(TEST_FILE_1.getFileName())));

        // now check both file contents
        byte[] expectedContent = Files.readAllBytes(ROOT_TEST_DIR1.resolve(TEST_FILE_1));
        byte[] actualContent = Files.readAllBytes(ROOT_TEST_DIR2.resolve(Config.DEFAULT.getSharedWithOthersReadWriteFolderName()).resolve(TEST_FILE_1.getFileName()));

        assertArrayEquals("Content is not equal", expectedContent, actualContent);

        // now check, that all delete events are ignored (incl children)
        CreateEvent createEvent = new CreateEvent(
                Paths.get(Config.DEFAULT.getSharedWithOthersReadWriteFolderName()).resolve(TEST_FILE_1.getFileName()),
                TEST_FILE_1.getFileName().toString(),
                "weIgnoreTheHash",
                System.currentTimeMillis()
        );

        IgnoreObjectStoreUpdateBusEvent expectedEvent1 = new IgnoreObjectStoreUpdateBusEvent(
                createEvent
        );

        List<IBusEvent> listener2Events = EVENT_BUS_LISTENER_2.getReceivedBusEvents();

        assertEquals("Listener should only contain all 1 events", 1, listener2Events.size());

        IBusEvent actualEvent1 = listener2Events.get(0);

        assertEquals("Expected create event", expectedEvent1.getEvent().getEventName(), actualEvent1.getEvent().getEventName());
        assertEquals("Expected path for testFile1", expectedEvent1.getEvent().getPath().toString(), actualEvent1.getEvent().getPath().toString());
        assertEquals("Expected name testFile1", expectedEvent1.getEvent().getName(), actualEvent1.getEvent().getName());
        assertEquals("Expected different hash", expectedEvent1.getEvent().getHash(), actualEvent1.getEvent().getHash());

        // now check that the object store contains the sharer
        PathObject sharedObject = OBJECT_STORE_2.getObjectManager().getObjectForPath(Paths.get(Config.DEFAULT.getSharedWithOthersReadWriteFolderName()).resolve(TEST_FILE_1.getFileName()).toString());

        assertNotNull("SharedObject should not be null", sharedObject);
        // since the client 2 did not share with anyone. Instead client1 shared with client2.
        // -> only the owner should be set
        assertFalse("File should not be shared", sharedObject.isShared());
        assertEquals("Owner should be equal to client1's user", CLIENT_1.getUser().getUserName(), sharedObject.getOwner());
        assertEquals("Sharer should not contain any user", 0, sharedObject.getSharers().size());

        EVENT_BUS_LISTENER_2.clear();
    }

    @Test
    public void testSendFileWhileModifying()
            throws InterruptedException, IOException, InputOutputException {
        EVENT_BUS_LISTENER_2.clear();

        ShareExchangeHandler shareExchangeHandler = new ShareExchangeHandler(
                CLIENT_1,
                new ClientLocation(CLIENT_2.getClientDeviceId(), CLIENT_2.getPeerAddress()),
                STORAGE_ADAPTER_1,
                OBJECT_STORE_1,
                TEST_FILE_1.toString(),
                TEST_FILE_1.getFileName().toString(),
                AccessType.WRITE,
                FILE_ID,
                true,
                EXCHANGE_ID
        );

        CLIENT_1.getObjectDataReplyHandler().addResponseCallbackHandler(EXCHANGE_ID, shareExchangeHandler);

        byte[] alteredContent = "Some altered content, forcing a re-download of the whole chunk".getBytes();

        Thread fileShareExchangeHandlerThread = new Thread(shareExchangeHandler);
        fileShareExchangeHandlerThread.setName("TEST-ShareExchangeHandler");
        fileShareExchangeHandlerThread.start();

        // just hope, that the file exchange is still running once we alter the content
        Thread changeFileThread = new Thread(() -> {

            try {
                // wait a bit so that the first chunk of the non-modified file could've been transferred
                Thread.sleep(50);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            try {
                Files.write(ROOT_TEST_DIR1.resolve(TEST_FILE_1), alteredContent);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        changeFileThread.start();

        // use a max of 30000 milliseconds to wait
        shareExchangeHandler.await();

        CLIENT_1.getObjectDataReplyHandler().removeResponseCallbackHandler(EXCHANGE_ID);

        assertTrue("ShareExchangeHandler should be completed after awaiting", shareExchangeHandler.isCompleted());

        ShareExchangeHandlerResult shareExchangeHandlerResult = shareExchangeHandler.getResult();

        assertNotNull("Result should not be null", shareExchangeHandlerResult);

        // check that shared folders are created
        assertTrue("SharedWithOthers (READ) should exist", Files.exists(ROOT_TEST_DIR2.resolve(Config.DEFAULT.getSharedWithOthersReadOnlyFolderName())));
        assertTrue("SharedWithOthers (READ-WRITE) should exist", Files.exists(ROOT_TEST_DIR2.resolve(Config.DEFAULT.getSharedWithOthersReadWriteFolderName())));

        assertTrue("File should exist in READ-WRITE folder", Files.exists(ROOT_TEST_DIR2.resolve(Config.DEFAULT.getSharedWithOthersReadWriteFolderName()).resolve(TEST_FILE_1.getFileName())));

        // now check both file contents
        byte[] actualContent = Files.readAllBytes(ROOT_TEST_DIR2.resolve(Config.DEFAULT.getSharedWithOthersReadWriteFolderName()).resolve(TEST_FILE_1.getFileName()));

        assertArrayEquals("Content is not equal", alteredContent, actualContent);

        EVENT_BUS_LISTENER_2.clear();
    }

    @Test
    public void testGetUniqueFileName()
            throws InputOutputException, IOException {
        ShareRequestHandler shareRequestHandler = new ShareRequestHandler();
        shareRequestHandler.setAccessManager(new AccessManager(OBJECT_STORE_1));
        shareRequestHandler.setGlobalEventBus(GLOBAL_EVENT_BUS_1);
        shareRequestHandler.setObjectStore(OBJECT_STORE_1);
        shareRequestHandler.setStorageAdapter(STORAGE_ADAPTER_1);
        shareRequestHandler.setClient(CLIENT_1);
        shareRequestHandler.setRequest(new ShareRequest(null, null, null, null, null, null, null, null, false, - 1L, - 1L, - 1L, null, - 1));

        String uniqueFile = shareRequestHandler.getUniqueFileName(TEST_UNIQUE_FILE.toString(), true);
        assertEquals("Filename should be equal before a file exists", TEST_UNIQUE_FILE.toString(), uniqueFile);

        Files.write(ROOT_TEST_DIR1.resolve(TEST_UNIQUE_FILE), "blub".getBytes());

        String uniqueFile2 = shareRequestHandler.getUniqueFileName(TEST_UNIQUE_FILE.toString(), true);
        assertEquals("Filename should be different after a file has been written", TEST_DIR_1.toString() + "/myUniqueFile (1).txt", uniqueFile2);

        Files.write(ROOT_TEST_DIR1.resolve(uniqueFile2), "blub2".getBytes());
        String uniqueFile3 = shareRequestHandler.getUniqueFileName(TEST_UNIQUE_FILE.toString(), true);
        assertEquals("Filename should be different after a file has been written", TEST_DIR_1.toString() + "/myUniqueFile (2).txt", uniqueFile3);

        // test directories
        String uniqueDir = shareRequestHandler.getUniqueFileName(TEST_UNIQUE_DIR.toString(), false);
        assertEquals("Filename should be equal before a file exists", TEST_UNIQUE_DIR.toString(), uniqueDir);

        Files.createDirectory(ROOT_TEST_DIR1.resolve(TEST_UNIQUE_DIR));

        String uniqueDir2 = shareRequestHandler.getUniqueFileName(TEST_UNIQUE_DIR.toString(), false);
        assertEquals("Filename should be different after a file has been written", TEST_DIR_1.toString() + "/myUniqueDir (1)", uniqueDir2);

        Files.createDirectory(ROOT_TEST_DIR1.resolve(uniqueDir2));
        String uniqueDir3 = shareRequestHandler.getUniqueFileName(TEST_UNIQUE_DIR.toString(), false);
        assertEquals("Filename should be different after a file has been written", TEST_DIR_1.toString() + "/myUniqueDir (2)", uniqueDir3);
    }
}