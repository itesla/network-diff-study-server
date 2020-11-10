/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.diffserver;

import com.powsybl.diffserver.repository.DiffStudyRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.data.cassandra.config.AbstractReactiveCassandraConfiguration;
import org.springframework.data.cassandra.config.CassandraClusterFactoryBean;
import org.springframework.data.cassandra.core.mapping.CassandraMappingContext;
import org.springframework.data.cassandra.core.mapping.SimpleUserTypeResolver;
import org.springframework.data.cassandra.repository.config.EnableReactiveCassandraRepositories;

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.eu>
 */

@Configuration
@PropertySource(value = {"classpath:cassandra.properties"})
@PropertySource(value = {"file:/config/cassandra.properties"}, ignoreResourceNotFound = true)
@EnableReactiveCassandraRepositories(basePackageClasses = DiffStudyRepository.class)
public class CassandraConfig extends AbstractReactiveCassandraConfiguration {

    @Override
    protected String getKeyspaceName() {
        return com.powsybl.diffserver.CassandraConstants.KEYSPACE_STUDY;
    }

    @Override
    protected boolean getMetricsEnabled() {
        return false;
    }

    @Bean
    public CassandraClusterFactoryBean cluster(Environment env) {
        CassandraClusterFactoryBean cluster = new CassandraClusterFactoryBean();
        cluster.setContactPoints(env.getRequiredProperty("cassandra.contact-points"));
        cluster.setPort(Integer.parseInt(env.getRequiredProperty("cassandra.port")));
        return cluster;
    }

    @Bean
    public CassandraMappingContext cassandraMapping(Environment env) {
        CassandraMappingContext mappingContext =  new CassandraMappingContext();
        mappingContext.setUserTypeResolver(new SimpleUserTypeResolver(cluster(env).getObject(), com.powsybl.diffserver.CassandraConstants.KEYSPACE_STUDY));
        return mappingContext;
    }
}
