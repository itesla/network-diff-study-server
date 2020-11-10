/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.diffserver.repository;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.eu>
 */

@Getter
@Setter
@AllArgsConstructor
@Table
public class DiffStudy implements Serializable {

    @PrimaryKey
    @Column("diffStudyName")
    private String diffStudyName;

    @Column("description")
    private String description;

    @Column("network1Uuid")
    private UUID network1Uuid;

    @Column("network1Id")
    private String network1Id;

    @Column("network2Uuid")
    private UUID network2Uuid;

    @Column("network2Id")
    private String network2Id;

    @Column("case1Uuid")
    private UUID case1Uuid;

    @Column("case1Format")
    private String case1Format;

    @Column("case2Uuid")
    private UUID case2Uuid;

    @Column("case2Format")
    private String case2Format;

    //a zone is a list of substation ids
    @Column("zone")
    private List<String> zone;
}

