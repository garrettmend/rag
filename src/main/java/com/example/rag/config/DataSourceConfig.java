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
 * Configures multiple database connections for the application.
 * 
 * WHY WE NEED THIS:
 * Our PostgreSQL database enforces Row-Level Security (RLS). We need two distinct
 * database roles to interact with it safely:
 * 1. An "App" connection (restricted by tenant ID for user web requests).
 * 2. A "Worker" connection (bypasses RLS to process background jobs for all tenants).
 */
@Configuration
public class DataSourceConfig {

    /**
     * Creates the primary database connection used for standard web requests.
     * 
     * @ConfigurationProperties tells Spring to read the URL, username, and password
     * from the "app.datasource" block in application.yml (logging in as the 'rag_app' role).
     * @Primary tells Spring to use this connection by default unless specified otherwise.
     */
    @Bean
    @Primary
    @ConfigurationProperties("app.datasource")
    public DataSource appDataSource() {
        return DataSourceBuilder.create().build();
    }

    /**
     * Creates the secondary database connection used for background processing.
     * 
     * Reads from the "worker.datasource" block in application.yml (logging in as
     * the 'rag_worker' role, whose explicit RLS policy can process every tenant).
     */
    @Bean
    @ConfigurationProperties("worker.datasource")
    public DataSource workerDataSource() {
        return DataSourceBuilder.create().build();
    }

    /**
     * Creates the JdbcTemplate (the tool used to execute SQL queries) for the App.
     * 
     * @Qualifier("appDataSource") ensures it specifically uses the restricted app connection.
     * @Primary ensures that if a Repository doesn't specify which template to use, 
     * it safely defaults to this restricted one.
     */
    @Bean
    @Primary
    public JdbcTemplate appJdbcTemplate(@Qualifier("appDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    /**
     * Creates the JdbcTemplate for background worker jobs.
     * 
     * Repositories that handle background processing will explicitly ask for this
     * "workerJdbcTemplate" to bypass RLS and process documents across all tenants.
     */
    @Bean
    public JdbcTemplate workerJdbcTemplate(@Qualifier("workerDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
