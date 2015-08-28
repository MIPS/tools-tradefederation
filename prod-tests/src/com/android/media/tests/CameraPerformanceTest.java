/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.media.tests;

import com.google.common.collect.ImmutableMultimap;

import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This test invocation runs android.hardware.camera2.cts.PerformanceTest -
 * Camera2 API use case performance KPIs, such as camera open time, session creation time,
 * shutter lag etc. The KPI data will be parsed and reported to dashboard.
 */
@OptionClass(alias = "camera-framework")
public class CameraPerformanceTest extends CameraTestBase {

    private static final String LOG_TAG = CameraPerformanceTest.class.getSimpleName();

    public CameraPerformanceTest() {
        // Set up the default test info. But this is subject to be overwritten by options passed
        // from commands.
        setTestPackage("com.android.cts.hardware");
        setTestClass("android.hardware.camera2.cts.PerformanceTest");
        setTestRunner("android.support.test.runner.AndroidJUnitRunner");
        setRuKey("camera_framework_performance");
        setTestTimeoutMs(10 * 60 * 1000); // 10 mins
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
        runInstrumentationTest(listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void handleTestRunEnded(ITestInvocationListener listener,
            Map<String, String> collectedMetrics) {
        // Report metrics at the end of test run.
        Map<String, String> result = parseResult(collectedMetrics);
        listener.testRunEnded(getTestDurationMs(), result);
    }

    /**
     * Parse Camera Performance KPIs result from the stdout generated by each test run.
     * Then put them all together to post the final report
     *
     * @return a {@link HashMap} that contains pairs of kpiName and kpiValue
     */
    private Map<String, String> parseResult(Map<String, String> metrics) {
        Map<String, String> resultsAll = new HashMap<String, String>();
        Camera2KpiParser parser = new Camera2KpiParser();
        for (Map.Entry<String, String> metric : metrics.entrySet()) {
            String testMethod = metric.getKey();
            String stdout = metric.getValue();
            CLog.d("test name %s", testMethod);
            CLog.d("stdout %s", stdout);

            // Get pairs of { KPI name, KPI value } from stdout that each test outputs.
            // Assuming that a device has both the front and back cameras, parser will return
            // 2 KPIs in HashMap. For an example of testCameraLaunch,
            //   {
            //     ("Camera 0 Camera launch time", "379.20"),
            //     ("Camera 1 Camera launch time", "272.80"),
            //   }
            Map<String, String> testKpis = parser.parse(stdout, testMethod);
            for (String k : testKpis.keySet()) {
                if (resultsAll.containsKey(k)) {
                    throw new RuntimeException(String.format("KPI name (%s) conflicts with " +
                            "the existing names. ", k));
                }
            }

            // Put each result together to post the final result
            resultsAll.putAll(testKpis);
        }
        return resultsAll;
    }

    /**
     * Data class of Camera Performance KPIs separated into summary and KPI items
     */
    private class Camera2KpiData {
        public class KpiItem {
            String testMethod; // "testSingleCapture"
            String code;       // "android.hardware.camera2.cts.PerformanceTest#testSingleCapture"
            String cameraId;   // "0" or "1"
            String kpiName;    // "Camera capture latency"
            String type;       // "lower_better"
            String unit;       // "ms"
            String kpiValue;   // "736.0 688.0 679.0 667.0 686.0"
            String key;        // primary key = cameraId + kpiName (+ testName if needed)
        }
        KpiItem summary;
        Map<String, KpiItem> kpiItems = new HashMap<String, KpiItem>();

        public KpiItem createItem(String testMethod, String code, String cameraId, String kpiName,
                String type, String unit, String kpiValue) {
            KpiItem kpiItem = new KpiItem();
            kpiItem.testMethod = testMethod;
            kpiItem.code = code;
            kpiItem.cameraId = cameraId;
            kpiItem.kpiName = kpiName;
            kpiItem.type = type;
            kpiItem.unit = unit;
            kpiItem.kpiValue = kpiValue;
            kpiItem.key = getRuSchemaKeyName(testMethod, cameraId, kpiName);
            return kpiItem;
        }

        private String getRuSchemaKeyName(String testMethod, String cameraId, String kpiName) {
            // Note 1: The key shouldn't contain ":" for side by side report.
            // Note 2: Two tests testReprocessingLatency & testReprocessingThroughput have the
            // same metric names to report results. To make the report key name distinct,
            // the test name is added as prefix for these tests for them.
            String key = String.format("Camera %s %s", cameraId, kpiName);
            final String[] TEST_NAMES_AS_PREFIX = {"testReprocessingLatency",
                    "testReprocessingThroughput"};
            for (String testName : TEST_NAMES_AS_PREFIX) {
                if (testMethod.endsWith(testName)) {
                    key = String.format("%s_%s", testName, key);
                    break;
                }
            }
            return key;
        }

        public List<KpiItem> getKpiItemsByKpiName(String kpiName) {
            List<KpiItem> matchedKpis = new ArrayList<KpiItem>();
            for (KpiItem log : kpiItems.values()) {
                if (log.kpiName.equals(kpiName)) {
                    matchedKpis.add(log);
                }
            }
            return matchedKpis;
        }

        public void setSummary(KpiItem kpiItem) {
            summary = kpiItem;
        }

        public void addKpi(KpiItem kpiItem) {
            kpiItems.put(kpiItem.key, kpiItem);
        }
    }

    /**
     * Parses the stdout generated by the underlying instrumentation test
     * and returns it to test runner for later reporting.
     *
     * Format:
     *   (summary message)| |(type)|(unit)|(value) ++++
     *   (code)|(message)|(type)|(unit)|(value)... +++
     *   ...
     *
     * Example:
     *   Camera launch average time for Camera 1| |lower_better|ms|586.6++++
     *   android.hardware.camera2.cts.PerformanceTest#testCameraLaunch:171|Camera 0: Camera open time|lower_better|ms|74.0 100.0 70.0 67.0 82.0 +++
     *   android.hardware.camera2.cts.PerformanceTest#testCameraLaunch:171|Camera 0: Camera configure stream time|lower_better|ms|9.0 5.0 5.0 8.0 5.0
     *   ...
     *
     * See also com.android.cts.util.ReportLog for the format detail.
     *
     */
    private class Camera2KpiParser {
        private static final String LOG_SEPARATOR = "\\+\\+\\+";
        private static final String SUMMARY_SEPARATOR = "\\+\\+\\+\\+";
        private static final String LOG_ELEM_SEPARATOR = "|";
        private final Pattern SUMMARY_REGEX = Pattern.compile(
                "^(?<message>[^|]+)\\| \\|(?<type>[^|]+)\\|(?<unit>[^|]+)\\|(?<value>[0-9 .]+)");
        private final Pattern KPI_REGEX = Pattern.compile(
                "^(?<code>[^|]+)\\|(?<message>[^|]+)\\|(?<type>[^|]+)\\|(?<unit>[^|]+)\\|(?<values>[0-9 .]+)");
        // eg. "Camera 0: Camera capture latency"
        private final Pattern KPI_KEY_REGEX = Pattern.compile(
                "^Camera\\s+(?<cameraId>\\d+):\\s+(?<kpiName>.*)");

        // KPIs to be reported. The key is test methods and the value is KPIs in the method.
        private final ImmutableMultimap<String, String> REPORTING_KPIS =
                new ImmutableMultimap.Builder<String, String>()
                    .put("testCameraLaunch", "Camera launch time")
                    .put("testCameraLaunch", "Camera start preview time")
                    .put("testSingleCapture", "Camera capture result latency")
                    .put("testReprocessingLatency", "YUV reprocessing shot to shot latency")
                    .put("testReprocessingLatency", "opaque reprocessing shot to shot latency")
                    .put("testReprocessingThroughput", "YUV reprocessing capture latency")
                    .put("testReprocessingThroughput", "opaque reprocessing capture latency")
                    .build();

        /**
         * Parse Camera Performance KPIs result first, then leave the only KPIs that matter.
         *
         * @param input String to be parsed
         * @param testMethod test method name used to leave the only metric that matters
         * @return a {@link HashMap} that contains kpiName and kpiValue
         */
        public Map<String, String> parse(String input, String testMethod) {
            Camera2KpiData parsed = parseToData(input, testMethod);
            return filter(parsed, testMethod);
        }

        private Map<String, String> filter(Camera2KpiData data, String testMethod) {
            Map<String, String> filtered = new HashMap<String, String>();
            for (String kpiToReport : REPORTING_KPIS.get(testMethod)) {
                // Report the only selected KPIs. Each KPI has two items for back and front cameras.
                List<Camera2KpiData.KpiItem> items = data.getKpiItemsByKpiName(kpiToReport);
                for (Camera2KpiData.KpiItem item : items) {
                    // item.getKey() should be unique to post results to dashboard.
                    filtered.put(item.key, item.kpiValue);
                }
            }
            return filtered;
        }

        private Camera2KpiData parseToData(String input, String testMethod) {
            Camera2KpiData data = new Camera2KpiData();

            // Split summary and KPIs from stdout passes as parameter.
            String[] output = input.split(SUMMARY_SEPARATOR);
            if (output.length != 2) {
                throw new RuntimeException("Value not in correct format");
            }
            Matcher summaryMatcher = SUMMARY_REGEX.matcher(output[0].trim());

            // Parse summary.
            // Example: "Camera launch average time for Camera 1| |lower_better|ms|586.6++++"
            if (summaryMatcher.matches()) {
                data.setSummary(data.createItem(testMethod, null,
                        "-1",
                        summaryMatcher.group("message"),
                        summaryMatcher.group("type"),
                        summaryMatcher.group("unit"),
                        summaryMatcher.group("value")));
            } else {
                // Currently malformed summary won't block a test as it's not used for report.
                CLog.w("Summary not in correct format");
            }

            // Parse KPIs.
            // Example: "android.hardware.camera2.cts.PerformanceTest#testCameraLaunch:171|Camera 0: Camera open time|lower_better|ms|74.0 100.0 70.0 67.0 82.0 +++"
            String[] kpis = output[1].split(LOG_SEPARATOR);
            for (String kpi : kpis) {
                Matcher kpiMatcher = KPI_REGEX.matcher(kpi.trim());
                if (kpiMatcher.matches()) {
                    String message = kpiMatcher.group("message");
                    Matcher m = KPI_KEY_REGEX.matcher(message.trim());
                    if (!m.matches()) {
                        throw new RuntimeException("Value not in correct format");
                    }
                    String cameraId = m.group("cameraId");
                    String kpiName = m.group("kpiName");
                    // get average of kpi values
                    String[] values = kpiMatcher.group("values").split("\\s+");
                    double sum = 0;
                    for (String value : values) {
                        sum += Double.parseDouble(value);
                    }
                    String kpiValue = String.format("%.1f", sum / values.length);
                    data.addKpi(data.createItem(testMethod,
                            kpiMatcher.group("code"),
                            cameraId,
                            kpiName,
                            kpiMatcher.group("type"),
                            kpiMatcher.group("unit"),
                            kpiValue));
                } else {
                    throw new RuntimeException("KPI not in correct format");
                }
            }
            return data;
        }
    }
}
