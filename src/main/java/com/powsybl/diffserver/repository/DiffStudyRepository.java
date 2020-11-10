/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.diffserver.repository;

import org.springframework.data.cassandra.repository.ReactiveCassandraRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.eu>
 */

@Repository
public interface DiffStudyRepository extends ReactiveCassandraRepository<DiffStudy, String> {

    Flux<DiffStudy> findAll();

    Mono<DiffStudy> findByDiffStudyName(String name);
}
