package com.settlement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
class VerificationJobIntegrationTest {

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Autowired
    @Qualifier("verificationJob")
    private Job verificationJob;

    @Autowired
    private DatabaseCleanup databaseCleanup;

    @BeforeEach
    void setUp() {
        databaseCleanup.execute();
        jobOperatorTestUtils.setJob(verificationJob);
    }

    @Test
    @DisplayName("시나리오 1: 인프라 검증 Job이 정상 실행되면 2개 Step이 모두 COMPLETED 된다")
    void should_complete_all_steps_when_verification_job_runs() throws Exception {
        JobExecution jobExecution = jobOperatorTestUtils.startJob();

        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jobExecution.getStepExecutions())
                .hasSize(2)
                .extracting(StepExecution::getStepName, StepExecution::getStatus)
                .containsExactlyInAnyOrder(
                        tuple("verifyDatabaseConnectionStep", BatchStatus.COMPLETED),
                        tuple("verifyJpaCrudStep", BatchStatus.COMPLETED)
                );
    }
}
