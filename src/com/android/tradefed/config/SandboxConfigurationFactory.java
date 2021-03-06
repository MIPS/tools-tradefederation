/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tradefed.config;

import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.sandbox.ISandbox;
import com.android.tradefed.sandbox.SandboxConfigDump.DumpCmd;
import com.android.tradefed.sandbox.SandboxConfigUtil;
import com.android.tradefed.util.FileUtil;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.keystore.IKeyStoreClient;

import java.io.File;
import java.util.Map;

/** Special Configuration factory to handle creation of configurations for Sandboxing purpose. */
public class SandboxConfigurationFactory extends ConfigurationFactory {

    private static SandboxConfigurationFactory sInstance = null;

    /** Get the singleton {@link IConfigurationFactory} instance. */
    public static SandboxConfigurationFactory getInstance() {
        if (sInstance == null) {
            sInstance = new SandboxConfigurationFactory();
        }
        return sInstance;
    }

    /** {@inheritDoc} */
    @Override
    ConfigurationDef getConfigurationDef(
            String name, boolean isGlobal, Map<String, String> templateMap)
            throws ConfigurationException {
        // TODO: Extend ConfigurationDef to possibly create a different IConfiguration type and
        // handle more elegantly the parent/subprocess incompatibilities.
        ConfigurationDef def = new ConfigurationDef(name);
        new ConfigLoader(isGlobal).loadConfiguration(name, def, null, templateMap);
        return def;
    }

    /**
     * Create a {@link IConfiguration} based on the command line and sandbox provided.
     *
     * @param args the command line for the run.
     * @param keyStoreClient the {@link IKeyStoreClient} where to load the key from.
     * @param sandbox the {@link ISandbox} used for the run.
     * @param runUtil the {@link IRunUtil} to run commands.
     * @return a {@link IConfiguration} valid for the sandbox.
     * @throws ConfigurationException
     */
    public IConfiguration createConfigurationFromArgs(
            String[] args, IKeyStoreClient keyStoreClient, ISandbox sandbox, IRunUtil runUtil)
            throws ConfigurationException {
        IConfiguration config = null;
        File xmlConfig = null;
        try {
            runUtil.unsetEnvVariable(GlobalConfiguration.GLOBAL_CONFIG_VARIABLE);
            File tfDir = sandbox.getTradefedEnvironment(args);
            // TODO: dump using the keystore too
            xmlConfig =
                    SandboxConfigUtil.dumpConfigForVersion(
                            tfDir, runUtil, args, DumpCmd.NON_VERSIONED_CONFIG);
            // Get the non version part of the configuration in order to do proper allocation
            // of devices and such.
            config =
                    super.createConfigurationFromArgs(
                            new String[] {xmlConfig.getAbsolutePath()}, null, keyStoreClient);
            // Reset the command line to the original one.
            config.setCommandLine(args);
            config.setConfigurationObject(Configuration.SANDBOX_TYPE_NAME, sandbox);
        } catch (ConfigurationException e) {
            CLog.e(e);
            sandbox.tearDown();
            throw e;
        } finally {
            FileUtil.deleteFile(xmlConfig);
        }
        return config;
    }
}
