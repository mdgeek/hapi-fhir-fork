package ca.uhn.fhir.jpa.search.reindex;

/*-
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2018 University Health Network
 * %%
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
 * #L%
 */

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.jpa.dao.BaseHapiFhirDao;
import ca.uhn.fhir.jpa.dao.DaoConfig;
import ca.uhn.fhir.jpa.dao.DaoRegistry;
import ca.uhn.fhir.jpa.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.dao.data.IForcedIdDao;
import ca.uhn.fhir.jpa.dao.data.IResourceReindexJobDao;
import ca.uhn.fhir.jpa.dao.data.IResourceTableDao;
import ca.uhn.fhir.jpa.entity.ForcedId;
import ca.uhn.fhir.jpa.entity.ResourceReindexJobEntity;
import ca.uhn.fhir.jpa.entity.ResourceTable;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import ca.uhn.fhir.util.StopWatch;
import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.commons.lang3.time.DateUtils;
import org.hibernate.search.util.impl.Executors;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceContextType;
import javax.persistence.Query;
import javax.transaction.Transactional;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

public class ResourceReindexingSvcImpl implements IResourceReindexingSvc {

	private static final Date BEGINNING_OF_TIME = new Date(0);
	private static final Logger ourLog = LoggerFactory.getLogger(ResourceReindexingSvcImpl.class);
	private final ReentrantLock myIndexingLock = new ReentrantLock();
	@Autowired
	private IResourceReindexJobDao myReindexJobDao;
	@Autowired
	private DaoConfig myDaoConfig;
	@Autowired
	private PlatformTransactionManager myTxManager;
	private TransactionTemplate myTxTemplate;
	private ThreadFactory myReindexingThreadFactory = new BasicThreadFactory.Builder().namingPattern("ResourceReindex-%d").build();
	private ThreadPoolExecutor myTaskExecutor;
	@Autowired
	private IResourceTableDao myResourceTableDao;
	@Autowired
	private DaoRegistry myDaoRegistry;
	@Autowired
	private IForcedIdDao myForcedIdDao;
	@Autowired
	private FhirContext myContext;
	@PersistenceContext(type = PersistenceContextType.TRANSACTION)
	private EntityManager myEntityManager;

	@VisibleForTesting
	void setReindexJobDaoForUnitTest(IResourceReindexJobDao theReindexJobDao) {
		myReindexJobDao = theReindexJobDao;
	}

	@VisibleForTesting
	void setDaoConfigForUnitTest(DaoConfig theDaoConfig) {
		myDaoConfig = theDaoConfig;
	}

	@VisibleForTesting
	void setTxManagerForUnitTest(PlatformTransactionManager theTxManager) {
		myTxManager = theTxManager;
	}

	@VisibleForTesting
	void setResourceTableDaoForUnitTest(IResourceTableDao theResourceTableDao) {
		myResourceTableDao = theResourceTableDao;
	}

	@VisibleForTesting
	void setDaoRegistryForUnitTest(DaoRegistry theDaoRegistry) {
		myDaoRegistry = theDaoRegistry;
	}

	@VisibleForTesting
	void setForcedIdDaoForUnitTest(IForcedIdDao theForcedIdDao) {
		myForcedIdDao = theForcedIdDao;
	}

	@VisibleForTesting
	void setContextForUnitTest(FhirContext theContext) {
		myContext = theContext;
	}

	@PostConstruct
	public void start() {
		myTxTemplate = new TransactionTemplate(myTxManager);
		initExecutor();
	}

	private void initExecutor() {
		// Create the threadpool executor used for reindex jobs
		int reindexThreadCount = myDaoConfig.getReindexThreadCount();
		RejectedExecutionHandler rejectHandler = new Executors.BlockPolicy();
		myTaskExecutor = new ThreadPoolExecutor(0, reindexThreadCount,
			0L, TimeUnit.MILLISECONDS,
			new LinkedBlockingQueue<>(100),
			myReindexingThreadFactory,
			rejectHandler
		);
	}

	@Override
	@Transactional(Transactional.TxType.REQUIRED)
	public void markAllResourcesForReindexing() {
		markAllResourcesForReindexing(null);
	}

	@Override
	@Transactional(Transactional.TxType.REQUIRED)
	public void markAllResourcesForReindexing(String theType) {
		String typeDesc;
		if (isNotBlank(theType)) {
			myReindexJobDao.markAllOfTypeAsDeleted(theType);
			typeDesc = theType;
		} else {
			myReindexJobDao.markAllOfTypeAsDeleted();
			typeDesc = "(any)";
		}

		ResourceReindexJobEntity job = new ResourceReindexJobEntity();
		job.setResourceType(theType);
		job.setThresholdHigh(DateUtils.addMinutes(new Date(), 5));
		job = myReindexJobDao.saveAndFlush(job);

		ourLog.info("Marking all resources of type {} for reindexing - Got job ID[{}]", typeDesc, job.getId());
	}

	@Override
	@Transactional(Transactional.TxType.NEVER)
	@Scheduled(fixedDelay = 10 * DateUtils.MILLIS_PER_SECOND)
	public Integer runReindexingPass() {
		if (myDaoConfig.isSchedulingDisabled()) {
			return null;
		}
		if (myIndexingLock.tryLock()) {
			try {
				return doReindexingPassInsideLock();
			} finally {
				myIndexingLock.unlock();
			}
		}
		return null;
	}

	private Integer doReindexingPassInsideLock() {
		expungeJobsMarkedAsDeleted();
		return runReindexJobs();
	}

	@Override
	public Integer forceReindexingPass() {
		myIndexingLock.lock();
		try {
			return doReindexingPassInsideLock();
		} finally {
			myIndexingLock.unlock();
		}
	}

	@Override
	public void cancelAndPurgeAllJobs() {
		ourLog.info("Cancelling and purging all resource reindexing jobs");
		myTxTemplate.execute(t -> {
			myReindexJobDao.markAllOfTypeAsDeleted();
			return null;
		});

		myTaskExecutor.shutdown();
		initExecutor();

		expungeJobsMarkedAsDeleted();
	}

	private Integer runReindexJobs() {
		Collection<ResourceReindexJobEntity> jobs = myTxTemplate.execute(t -> myReindexJobDao.findAll(PageRequest.of(0, 10), false));
		assert jobs != null;

		int count = 0;
		for (ResourceReindexJobEntity next : jobs) {

			if (next.getThresholdHigh().getTime() < System.currentTimeMillis()) {
				markJobAsDeleted(next);
				continue;
			}

			count += runReindexJob(next);
		}
		return count;
	}

	private void markJobAsDeleted(ResourceReindexJobEntity next) {
		myTxTemplate.execute(t -> {
			myReindexJobDao.markAsDeletedById(next.getId());
			return null;
		});
	}

	private int runReindexJob(ResourceReindexJobEntity theJob) {
		if (theJob.getSuspendedUntil() != null) {
			if (theJob.getSuspendedUntil().getTime() > System.currentTimeMillis()) {
				return 0;
			}
		}

		ourLog.info("Performing reindex pass for JOB[{}]", theJob.getId());
		StopWatch sw = new StopWatch();
		AtomicInteger counter = new AtomicInteger();

		// Calculate range
		Date low = theJob.getThresholdLow() != null ? theJob.getThresholdLow() : BEGINNING_OF_TIME;
		Date high = theJob.getThresholdHigh();

		// Query for resources within threshold
		Slice<Long> range = myTxTemplate.execute(t -> {
			PageRequest page = PageRequest.of(0, 10000);
			if (isNotBlank(theJob.getResourceType())) {
				return myResourceTableDao.findIdsOfResourcesWithinUpdatedRangeOrderedFromOldest(page, theJob.getResourceType(), low, high);
			} else {
				return myResourceTableDao.findIdsOfResourcesWithinUpdatedRangeOrderedFromOldest(page, low, high);
			}
		});
		Validate.notNull(range);
		int count = range.getNumberOfElements();

		// Submit each resource requiring reindexing
		List<Future<Date>> futures = range
			.stream()
			.map(t -> myTaskExecutor.submit(new ResourceReindexingTask(t, counter)))
			.collect(Collectors.toList());

		Date latestDate = null;
		boolean haveMultipleDates = false;
		for (Future<Date> next : futures) {
			Date nextDate;
			try {
				nextDate = next.get();
			} catch (Exception e) {
				ourLog.error("Failure reindexing", e);
				Date suspendedUntil = DateUtils.addMinutes(new Date(), 1);
				myTxTemplate.execute(t -> {
					myReindexJobDao.setSuspendedUntil(suspendedUntil);
					return null;
				});
				return counter.get();
			}

			if (nextDate != null) {
				if (latestDate != null) {
					if (latestDate.getTime() != nextDate.getTime()) {
						haveMultipleDates = true;
					}
				}
				if (latestDate == null || latestDate.getTime() < nextDate.getTime()) {
					latestDate = new Date(nextDate.getTime());
				}
			}
		}

		// Just in case we end up in some sort of infinite loop. This shouldn't happen, and couldn't really
		// happen unless there were 10000 resources with the exact same update time down to the
		// millisecond.
		Date newLow;
		if (latestDate == null) {
			markJobAsDeleted(theJob);
			return 0;
		}
		if (latestDate.getTime() == low.getTime()) {
			ourLog.error("Final pass time for reindex JOB[{}] has same ending low value: {}", theJob.getId(), latestDate);
			newLow = new Date(latestDate.getTime() + 1);
		} else if (!haveMultipleDates) {
			newLow = new Date(latestDate.getTime() + 1);
		} else {
			newLow = latestDate;
		}

		myTxTemplate.execute(t -> {
			myReindexJobDao.setThresholdLow(theJob.getId(), newLow);
			return null;
		});

		ourLog.info("Completed pass of reindex JOB[{}] - Indexed {} resources in {} ({} / sec) - Have indexed until: {}", theJob.getId(), count, sw.toString(), sw.formatThroughput(count, TimeUnit.SECONDS), theJob.getThresholdLow());
		return counter.get();
	}

	private void expungeJobsMarkedAsDeleted() {
		myTxTemplate.execute(t -> {
			Collection<ResourceReindexJobEntity> toDelete = myReindexJobDao.findAll(PageRequest.of(0, 10), true);
			toDelete.forEach(job -> {
				ourLog.info("Purging deleted job[{}]", job.getId());
				myReindexJobDao.deleteById(job.getId());
			});
			return null;
		});
	}

	@SuppressWarnings("JpaQlInspection")
	private void markResourceAsIndexingFailed(final long theId) {
		TransactionTemplate txTemplate = new TransactionTemplate(myTxManager);
		txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		txTemplate.execute((TransactionCallback<Void>) theStatus -> {
			ourLog.info("Marking resource with PID {} as indexing_failed", new Object[]{theId});

			myResourceTableDao.updateIndexStatus(theId, BaseHapiFhirDao.INDEX_STATUS_INDEXING_FAILED);

			Query q = myEntityManager.createQuery("DELETE FROM ResourceTag t WHERE t.myResourceId = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			q = myEntityManager.createQuery("DELETE FROM ResourceIndexedSearchParamCoords t WHERE t.myResourcePid = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			q = myEntityManager.createQuery("DELETE FROM ResourceIndexedSearchParamDate t WHERE t.myResourcePid = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			q = myEntityManager.createQuery("DELETE FROM ResourceIndexedSearchParamNumber t WHERE t.myResourcePid = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			q = myEntityManager.createQuery("DELETE FROM ResourceIndexedSearchParamQuantity t WHERE t.myResourcePid = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			q = myEntityManager.createQuery("DELETE FROM ResourceIndexedSearchParamString t WHERE t.myResourcePid = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			q = myEntityManager.createQuery("DELETE FROM ResourceIndexedSearchParamToken t WHERE t.myResourcePid = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			q = myEntityManager.createQuery("DELETE FROM ResourceIndexedSearchParamUri t WHERE t.myResourcePid = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			q = myEntityManager.createQuery("DELETE FROM ResourceLink t WHERE t.mySourceResourcePid = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			q = myEntityManager.createQuery("DELETE FROM ResourceLink t WHERE t.myTargetResourcePid = :id");
			q.setParameter("id", theId);
			q.executeUpdate();

			return null;
		});
	}

	private class ResourceReindexingTask implements Callable<Date> {
		private final Long myNextId;
		private final AtomicInteger myCounter;
		private Date myUpdated;

		ResourceReindexingTask(Long theNextId, AtomicInteger theCounter) {
			myNextId = theNextId;
			myCounter = theCounter;
		}


		@SuppressWarnings("unchecked")
		private <T extends IBaseResource> void doReindex(ResourceTable theResourceTable, T theResource) {
			RuntimeResourceDefinition resourceDefinition = myContext.getResourceDefinition(theResource.getClass());
			Class<T> resourceClass = (Class<T>) resourceDefinition.getImplementingClass();
			final IFhirResourceDao<T> dao = myDaoRegistry.getResourceDao(resourceClass);
			dao.reindex(theResource, theResourceTable);

			myCounter.incrementAndGet();
		}

		@Override
		public Date call() {
			Throwable reindexFailure;
			try {
				reindexFailure = myTxTemplate.execute(t -> {
					ResourceTable resourceTable = myResourceTableDao.findById(myNextId).orElseThrow(IllegalStateException::new);
					myUpdated = resourceTable.getUpdatedDate();

					try {
						/*
						 * This part is because from HAPI 1.5 - 1.6 we changed the format of forced ID to be "type/id" instead of just "id"
						 */
						ForcedId forcedId = resourceTable.getForcedId();
						if (forcedId != null) {
							if (isBlank(forcedId.getResourceType())) {
								ourLog.info("Updating resource {} forcedId type to {}", forcedId.getForcedId(), resourceTable.getResourceType());
								forcedId.setResourceType(resourceTable.getResourceType());
								myForcedIdDao.save(forcedId);
							}
						}

						IFhirResourceDao<?> dao = myDaoRegistry.getResourceDao(resourceTable.getResourceType());
						IBaseResource resource = dao.toResource(resourceTable, false);
						if (resource == null) {
							throw new InternalErrorException("Could not find resource version " + resourceTable.getIdDt().toUnqualified().getValue() + " in database");
						}
						doReindex(resourceTable, resource);
						return null;

					} catch (Exception e) {
						ourLog.error("Failed to index resource {}: {}", resourceTable.getIdDt(), e.toString(), e);
						t.setRollbackOnly();
						return e;
					}
				});
			} catch (ResourceVersionConflictException e) {
				/*
				 * We reindex in multiple threads, so it's technically possible that two threads try
				 * to index resources that cause a constraint error now (i.e. because a unique index has been
				 * added that didn't previously exist). In this case, one of the threads would succeed and
				 * not get this error, so we'll let the other one fail and try
				 * again later.
				 */
				ourLog.info("Failed to reindex {} because of a version conflict. Leaving in unindexed state: {}", e.getMessage());
				reindexFailure = null;
			}

			if (reindexFailure != null) {
				ourLog.info("Setting resource PID[{}] status to ERRORED", myNextId);
				markResourceAsIndexingFailed(myNextId);
			}

			return myUpdated;
		}
	}
}
