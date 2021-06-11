/**
 * Copyright (c) 2020-2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.diffserver;

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.eu>
 */
class LineDiffData {
    final String lineId;
    final String pDelta1;
    final String qDelta1;
    final String iDelta1;
    final String pDelta2;
    final String qDelta2;
    final String iDelta2;
    final String pDelta1Perc;
    final String qDelta1Perc;
    final String iDelta1Perc;
    final String pDelta2Perc;
    final String qDelta2Perc;
    final String iDelta2Perc;

    public LineDiffData(String lineId, String pDelta1, String qDelta1, String iDelta1, String pDelta2, String qDelta2, String iDelta2, String pDelta1Perc, String qDelta1Perc, String iDelta1Perc, String pDelta2Perc, String qDelta2Perc, String iDelta2Perc) {
        this.lineId = lineId;
        this.pDelta1 = pDelta1;
        this.qDelta1 = qDelta1;
        this.iDelta1 = iDelta1;
        this.pDelta2 = pDelta2;
        this.qDelta2 = qDelta2;
        this.iDelta2 = iDelta2;
        this.pDelta1Perc = pDelta1Perc;
        this.qDelta1Perc = qDelta1Perc;
        this.iDelta1Perc = iDelta1Perc;
        this.pDelta2Perc = pDelta2Perc;
        this.qDelta2Perc = qDelta2Perc;
        this.iDelta2Perc = iDelta2Perc;
    }

    public String getLineId() {
        return lineId;
    }

    public String getpDelta1() {
        return pDelta1;
    }

    public String getqDelta1() {
        return qDelta1;
    }

    public String getiDelta1() {
        return iDelta1;
    }

    public String getpDelta2() {
        return pDelta2;
    }

    public String getpDelta1Perc() {
        return pDelta1Perc;
    }

    public String getqDelta1Perc() {
        return qDelta1Perc;
    }

    public String getiDelta2() {
        return iDelta2;
    }

    public String getiDelta1Perc() {
        return iDelta1Perc;
    }

    public String getpDelta2Perc() {
        return pDelta2Perc;
    }

    public String getqDelta2Perc() {
        return qDelta2Perc;
    }

    public String getiDelta2Perc() {
        return iDelta2Perc;
    }

    public String getqDelta2() {
        return qDelta2;
    }

    @Override
    public String toString() {
        return "LineDiffData{" +
                "lineId='" + lineId + '\'' +
                '}';
    }
}
