package com.example.rag.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Configures the two PostgreSQL connections the application needs under row-level security.
 * The primary "app" connection logs in as the tenant-restricted rag_app role and serves web requests.
 * The "worker" connection logs in as the rag_worker role, whose RLS policy lets background jobs process every tenant.
 */
@Configuration
public class DataSourceConfig {

    /**
     * Creates the primary, tenant-restricted data source from the {@code app.datasource} properties.
     *
     * @return the rag_app data source, used by default wherever no qualifier is given
     */
    @Bean
    @Primary
    @ConfigurationProperties("app.datasource")
    public DataSource appDataSource() {
        return DataSourceBuilder.create().build();
    }

    /**
     * Creates the background-processing data source from the {@code worker.datasource} properties.
     *
     * @return the rag_worker data source
     */
    @Bean
    @ConfigurationProperties("worker.datasource")
    public DataSource workerDataSource() {
        return DataSourceBuilder.create().build();
    }

    /**
     * Creates the default JdbcTemplate, bound to the restricted app connection so unqualified injections stay safe.
     *
     * @param ds the app data source
     * @return a JdbcTemplate for tenant-scoped queries
     */
    @Bean
    @Primary
    public JdbcTemplate appJdbcTemplate(@Qualifier("appDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    /**
     * Creates the JdbcTemplate that worker repositories request explicitly to process documents across tenants.
     *
     * @param ds the worker data source
     * @return a JdbcTemplate for background ingestion queries
     */
    @Bean
    public JdbcTemplate workerJdbcTemplate(@Qualifier("workerDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
