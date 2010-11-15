/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tradefed.testtype;

import com.android.ddmlib.FileListingService;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.testrunner.ITestRunListener;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.DeviceUnresponsiveException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.MockFileUtil;
import com.android.tradefed.result.ITestInvocationListener;

import org.easymock.EasyMock;

import java.util.ArrayList;
import java.util.Collection;

import junit.framework.TestCase;


/**
 * Unit tests for {@link GTestTest}.
 */
public class GTestTest extends TestCase {
    private ITestInvocationListener mMockInvocationListener = null;
    private IShellOutputReceiver mMockReceiver = null;
    private ITestDevice mMockITestDevice = null;
    private Collection<ITestRunListener> mMockListeners;
    private GTest mGTest;

    /**
     * Helper to initialize the various EasyMocks we'll need.
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mMockInvocationListener = EasyMock.createMock(ITestInvocationListener.class);
        mMockListeners = new ArrayList<ITestRunListener>(1);
        mMockListeners.add(mMockInvocationListener);
        mMockReceiver = EasyMock.createMock(IShellOutputReceiver.class);
        mMockITestDevice = EasyMock.createMock(ITestDevice.class);
        EasyMock.expect(mMockITestDevice.getSerialNumber()).andStubReturn("serial");
        mGTest = new GTest() {
            @Override
            IShellOutputReceiver createResultParser(String runName,
                    Collection<ITestRunListener> listeners) {
                return mMockReceiver;
            }
        };
        mGTest.setDevice(mMockITestDevice);
    }

    /**
     * Helper that replays all mocks.
     */
    private void replayMocks() {
      EasyMock.replay(mMockInvocationListener, mMockITestDevice, mMockReceiver);
    }

    /**
     * Helper that verifies all mocks.
     */
    private void verifyMocks() {
      EasyMock.verify(mMockInvocationListener, mMockITestDevice, mMockReceiver);
    }

    /**
     * Test the run method for a couple tests
     */
    public void testRun() throws DeviceNotAvailableException {
        final String nativeTestPath = GTest.DEFAULT_NATIVETEST_PATH;
        final String test1 = "test1";
        final String test2 = "test2";

        MockFileUtil.setMockDirContents(mMockITestDevice, nativeTestPath, test1, test2);
        EasyMock.expect(mMockITestDevice.executeShellCommand(EasyMock.contains("chmod")))
                .andReturn("")
                .times(2);
        mMockITestDevice.executeShellCommand(EasyMock.contains(test1),
                EasyMock.same(mMockReceiver), EasyMock.anyInt(), EasyMock.anyInt());
        mMockITestDevice.executeShellCommand(EasyMock.contains(test2),
                EasyMock.same(mMockReceiver), EasyMock.anyInt(), EasyMock.anyInt());

        replayMocks();

        mGTest.run(mMockInvocationListener);
        verifyMocks();
    }

    /**
     * Test the run method when module name is specified
     */
    public void testRun_moduleName() throws DeviceNotAvailableException {
        final String module = "test1";
        final String modulePath = String.format("%s%s%s",
                GTest.DEFAULT_NATIVETEST_PATH, FileListingService.FILE_SEPARATOR, module);
        MockFileUtil.setMockDirContents(mMockITestDevice, modulePath, new String[] {});

        mGTest.setModuleName(module);

        // expect test1 to be executed
        EasyMock.expect(mMockITestDevice.executeShellCommand(EasyMock.contains("chmod")))
                .andReturn("");
        mMockITestDevice.executeShellCommand(EasyMock.contains(modulePath),
                EasyMock.same(mMockReceiver), EasyMock.anyInt(), EasyMock.anyInt());

        replayMocks();

        mGTest.run(mMockInvocationListener);
        verifyMocks();
    }

    /**
     * Test the run method for a test in a subdirectory
     */
    public void testRun_nested() throws DeviceNotAvailableException {
        final String nativeTestPath = GTest.DEFAULT_NATIVETEST_PATH;
        final String subFolderName = "subFolder";
        final String test1 = "test1";
        final String test1Path = String.format("%s%s%s%s%s", nativeTestPath,
                FileListingService.FILE_SEPARATOR,
                subFolderName,
                FileListingService.FILE_SEPARATOR, test1);

        MockFileUtil.setMockDirPath(mMockITestDevice, nativeTestPath, subFolderName, test1);
        EasyMock.expect(mMockITestDevice.executeShellCommand(EasyMock.contains("chmod")))
                .andReturn("");
        mMockITestDevice.executeShellCommand(EasyMock.contains(test1Path),
                EasyMock.same(mMockReceiver), EasyMock.anyInt(), EasyMock.anyInt());

        replayMocks();

        mGTest.run(mMockInvocationListener);
        verifyMocks();
    }

    /**
     * Helper function to do the actual filtering test.
     *
     * @param filterString The string to search for in the Mock, to verify filtering was called
     * @throws DeviceNotAvailableException
     */
    private void doTestFilter(String filterString) throws DeviceNotAvailableException {
        // configure the mock file system to have a single test
        MockFileUtil.setMockDirContents(mMockITestDevice, GTest.DEFAULT_NATIVETEST_PATH, "test1");

        EasyMock.expect(mMockITestDevice.executeShellCommand(EasyMock.contains("chmod")))
                    .andReturn("");
            mMockITestDevice.executeShellCommand(EasyMock.contains(filterString),
                    EasyMock.same(mMockReceiver), EasyMock.anyInt(), EasyMock.anyInt());
        replayMocks();
        mGTest.run(mMockInvocationListener);

        verifyMocks();
    }

    /**
     * Test the positive filtering of test methods.
     */
    public void testPositiveFilter() throws DeviceNotAvailableException {
        String posFilter = "abbccc";
        mGTest.setTestNamePositiveFilter(posFilter);

        doTestFilter(posFilter);
    }

    /**
     * Test the negative filtering of test methods.
     */
    public void testNegativeFilter() throws DeviceNotAvailableException {
        String negFilter = "*don?tRunMe*";
        mGTest.setTestNameNegativeFilter(negFilter);

        doTestFilter("-*." + negFilter);
    }

    /**
     * Test simultaneous positive and negative filtering of test methods.
     */
    public void testPositiveAndNegativeFilter() throws DeviceNotAvailableException {
         String posFilter = "pleaseRunMe";
        String negFilter = "dontRunMe";
        mGTest.setTestNamePositiveFilter(posFilter);
        mGTest.setTestNameNegativeFilter(negFilter);

        String filter = String.format("%s-*.%s", posFilter, negFilter);
        doTestFilter(filter);
    }

    /**
     * Test the run method for a test which times out
     */
    public void testRun_timeout() throws DeviceNotAvailableException {
        final String nativeTestPath = GTest.DEFAULT_NATIVETEST_PATH;
        final String test1 = "test1";

        MockFileUtil.setMockDirContents(mMockITestDevice, nativeTestPath, test1);
        EasyMock.expect(mMockITestDevice.executeShellCommand(EasyMock.contains("chmod")))
                .andReturn("");
        mMockITestDevice.executeShellCommand(EasyMock.contains(test1),
                EasyMock.same(mMockReceiver), EasyMock.anyInt(), EasyMock.anyInt());
        // for now, test timeout will result in DeviceUnresponsiveException
        // expect that this exception does not get thrown up
        EasyMock.expectLastCall().andThrow(new DeviceUnresponsiveException());

        replayMocks();

        mGTest.run(mMockInvocationListener);
        verifyMocks();
    }
}
