/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mybatch.runtime.runner;

import javax.batch.api.StepListener;
import javax.batch.operations.JobOperator;

import org.mybatch.job.Batchlet;
import org.mybatch.job.Chunk;
import org.mybatch.job.Step;
import org.mybatch.runtime.StepExecutionImpl;
import org.mybatch.runtime.context.StepContextImpl;
import org.mybatch.util.BatchLogger;

import static org.mybatch.util.BatchLogger.LOGGER;

public final class StepExecutionRunner extends AbstractRunner implements Runnable {
    private Step step;
    private StepExecutionImpl stepExecution;
    private StepContextImpl stepContext;
    private JobExecutionRunner jobExecutionRunner;
    private Object stepResult;

    public StepExecutionRunner(Step step, StepExecutionImpl stepExecution, StepContextImpl stepContext, JobExecutionRunner jobExecutionRunner) {
        this.step = step;
        this.stepExecution = stepExecution;
        this.stepContext = stepContext;
        this.jobExecutionRunner = jobExecutionRunner;
    }

    public StepContextImpl getStepContext() {
        return stepContext;
    }

    public JobExecutionRunner getJobExecutionRunner() {
        return jobExecutionRunner;
    }

    @Override
    public void run() {
        stepContext.setBatchStatus(JobOperator.BatchStatus.STARTED);
        Chunk chunk = step.getChunk();
        Batchlet batchlet = step.getBatchlet();
        if (chunk == null && batchlet == null) {
            stepContext.setBatchStatus(JobOperator.BatchStatus.ABANDONED);
            LOGGER.stepContainsNoChunkOrBatchlet(step.getId());
            return;
        }

        if (chunk != null && batchlet != null) {
            stepContext.setBatchStatus(JobOperator.BatchStatus.ABANDONED);
            LOGGER.cannotContainBothChunkAndBatchlet(step.getId());
            return;
        }

        for (StepListener l : stepContext.getStepListeners()) {
            try {
                l.beforeStep();
            } catch (Throwable e) {
                BatchLogger.LOGGER.failToRunJob(e, l, "beforeStep");
                stepContext.setBatchStatus(JobOperator.BatchStatus.FAILED);
                return;
            }
        }


        BatchletRunner batchletRunner = new BatchletRunner(batchlet, this);
        stepResult = batchletRunner.call();

        //TODO handle chunk type step


        for (StepListener l : stepContext.getStepListeners()) {
            try {
                l.afterStep();
            } catch (Throwable e) {
                BatchLogger.LOGGER.failToRunJob(e, l, "afterStep");
                stepContext.setBatchStatus(JobOperator.BatchStatus.FAILED);
                return;
            }
        }

        if (stepContext.getBatchStatus() == JobOperator.BatchStatus.STARTED) {  //has not been marked as failed, stopped or abandoned
            stepContext.setBatchStatus(JobOperator.BatchStatus.COMPLETED);
            stepContext.setExitStatus(JobOperator.BatchStatus.COMPLETED.name());
            stepContext.getJobContext().setBatchStatus(JobOperator.BatchStatus.COMPLETED);
            stepContext.getJobContext().setExitStatus(JobOperator.BatchStatus.COMPLETED.name());
        }

        if (stepContext.getBatchStatus() == JobOperator.BatchStatus.COMPLETED) {
            String next = step.getNext();
            if (next == null) {
                next = resolveControlElements(step.getControlElements(), stepContext);
            }
            jobExecutionRunner.runJobElement(next, stepExecution);
        }
    }

}
