/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.android.tradefed;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IFolderBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.testtype.IBuildReceiver;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.StreamUtil;
import com.android.tradefed.util.SubprocessTestResultsParser;

import junit.framework.Assert;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link IRemoteTest} for running tests against a separate TF installation.
 * <p/>
 * Launches an external java process to run the tests. Used for running the TF unit or
 * functional tests continuously.
 */
public class TfTestLauncher implements IRemoteTest, IBuildReceiver {

    @Option(name = "max-run-time", description =
            "the maximum time in minutes to allow for a TF test run.", isTimeVal = true)
    private long mMaxTfRunTimeMin = 20;

    @Option(name = "remote-debug", description =
            "start the TF java process in remote debug mode.")
    private boolean mRemoteDebug = false;

    private IBuildInfo mBuildInfo;

    @Option(name = "config-name", description = "the config that runs the TF tests")
    private String mConfigName;

    @Option(name = "sub-branch", description = "the branch to be provided to the sub invocation, "
            + "if null, the branch in build info will be used.")
    private String mSubBranch = null;

    @Option(name = "sub-build-flavor", description = "the build flavor to be provided to the "
            + "sub invocation, if null, the build flavor in build info will be used.")
    private String mSubBuildFlavor = null;

    @Option(name = "sub-build-id", description = "the build id that the sub invocation will try "
            + "to use in case where it needs its own device.")
    private String mSubBuildId = null;

    @Option(name = "use-virtual-device", description =
            "flag if the subprocess is going to need to instantiate a virtual device to run.")
    private boolean mNeedDevice = false;

    @Option(name = "sub-apk-path", description = "The name of all the Apks that needs to be "
            + "installed by the subprocess invocation. Apk need to be inside the downloaded zip. "
            + "Can be repeated.")
    private List<String> mSubApkPath = new ArrayList<String>();

    private static final String TF_GLOBAL_CONFIG = "TF_GLOBAL_CONFIG";

    /**
     * {@inheritDoc}
     */
    @Override
    public void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
        Assert.assertNotNull(mBuildInfo);
        Assert.assertNotNull(mConfigName);
        IFolderBuildInfo tfBuild = (IFolderBuildInfo)mBuildInfo;
        String jarClasspath = FileUtil.getPath(tfBuild.getRootDir().getAbsolutePath(), "*");
        List<String> args = new ArrayList<String>();
        args.add("java");
        if (mRemoteDebug) {
            args.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=10088");
        }
        args.add("-cp");
        args.add(jarClasspath);
        args.add("com.android.tradefed.command.CommandRunner");
        args.add(mConfigName);
        if (!mNeedDevice) {
            args.add("-n");
        } else {
            // if it needs a device we also enable more logs
            args.add("--log-level");
            args.add("VERBOSE");
            args.add("--log-level-display");
            args.add("VERBOSE");
        }
        args.add("--test-tag");
        args.add(mBuildInfo.getTestTag());
        if (mSubBuildId != null) {
            args.add("--build-id");
            args.add(mSubBuildId);
        }
        args.add("--branch");
        if (mSubBranch != null) {
            args.add(mSubBranch);
        } else if (mBuildInfo.getBuildBranch() != null) {
            args.add(mBuildInfo.getBuildBranch());
        } else {
            throw new RuntimeException("Branch option is required for the sub invocation.");
        }
        args.add("--build-flavor");
        if (mSubBuildFlavor != null) {
            args.add(mSubBuildFlavor);
        } else if (mBuildInfo.getBuildFlavor() != null) {
            args.add(mBuildInfo.getBuildFlavor());
        } else {
            throw new RuntimeException("Build flavor option is required for the sub invocation.");
        }

        for (String apk : mSubApkPath) {
            args.add("--apk-path");
            String apkPath = String.format("%s%s%s",
                    ((IFolderBuildInfo)mBuildInfo).getRootDir().getAbsolutePath(),
                    File.separator, apk);
            args.add(apkPath);
        }

        File stdoutFile = null;
        File stderrFile = null;
        File eventFile = null;
        SubprocessTestResultsParser eventParser = new SubprocessTestResultsParser(listener);
        FileOutputStream stdout = null;
        FileOutputStream stderr = null;

        IRunUtil runUtil = new RunUtil();
        // clear the TF_GLOBAL_CONFIG env, so another tradefed will not reuse the global config file
        runUtil.unsetEnvVariable(TF_GLOBAL_CONFIG);

        try {
            stdoutFile = FileUtil.createTempFile("stdout_subprocess_", ".log");
            stderrFile = FileUtil.createTempFile("stderr_subprocess_", ".log");
            eventFile = FileUtil.createTempFile("event_subprocess_", ".log");
            stdout = new FileOutputStream(stdoutFile);
            stderr = new FileOutputStream(stderrFile);

            args.add("--subprocess-report-file");
            args.add(eventFile.getAbsolutePath());

            CommandResult result = runUtil.runTimedCmd(mMaxTfRunTimeMin * 60 * 1000, stdout, stderr,
                    args.toArray(new String[0]));
            if (result.getStatus().equals(CommandStatus.SUCCESS)) {
                CLog.d("Successfully ran TF tests for build %s", mBuildInfo.getBuildId());
            } else {
                CLog.w("Failed ran TF tests for build %s, status %s",
                        mBuildInfo.getBuildId(), result.getStatus());
                CLog.v("TF tests output:\nstdout:\n%s\nstderror:\n%s",
                        result.getStdout(), result.getStderr());
                throw new RuntimeException(
                        String.format("%s Tests subprocess failed due to: %s\n", mConfigName,
                                result.getStatus()));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            StreamUtil.close(stdout);
            StreamUtil.close(stderr);
            logAndCleanFile(stdoutFile, listener);
            logAndCleanFile(stderrFile, listener);
            if (eventFile != null) {
                eventParser.parseFile(eventFile);
                logAndCleanFile(eventFile, listener);
            }
        }
    }

    private void logAndCleanFile(File fileToExport, ITestInvocationListener listener) {
        if (fileToExport != null) {
            FileInputStreamSource stderrInputStream = new FileInputStreamSource(fileToExport);
            listener.testLog(fileToExport.getName(), LogDataType.TEXT, stderrInputStream);
            stderrInputStream.cancel();
            FileUtil.deleteFile(fileToExport);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBuild(IBuildInfo buildInfo) {
        mBuildInfo = buildInfo;
    }
}
