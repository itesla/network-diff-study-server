/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.diffserver;

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.eu>
 */
public final class DiffStudyConstants {

    private DiffStudyConstants() {
    }

    public static final String CASE_API_VERSION = "v1";
    static final String SINGLE_LINE_DIAGRAM_API_VERSION = "v1";
    static final String NETWORK_CONVERSION_API_VERSION = "v1";

    static final String CASE_UUID = "caseUuid";

    static final String NETWORK1_UUID = "network1Uuid";
    static final String CASE1_UUID = "case1Uuid";

    static final String NETWORK2_UUID = "network2Uuid";
    static final String CASE2_UUID = "case2Uuid";

    static final String DIFF_STUDY_ALREADY_EXISTS = "DIFF STUDY ALREADY EXISTS";
    static final String DIFF_STUDY_DOESNT_EXISTS = "DIFF STUDY DOESN'T EXISTS";
    static final String CASE_DOESNT_EXISTS = "CASE DOESN'T EXISTS";
    static final String NOT_ALLOWED = "NOT ALLOWED";

    static final String DELIMITER = "/";
}
