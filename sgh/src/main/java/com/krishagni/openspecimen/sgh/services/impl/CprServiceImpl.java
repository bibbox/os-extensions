package com.krishagni.openspecimen.sgh.services.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;

import com.krishagni.catissueplus.core.administrative.domain.Site;
import com.krishagni.catissueplus.core.biospecimen.ConfigParams;
import com.krishagni.catissueplus.core.biospecimen.domain.CollectionProtocol;
import com.krishagni.catissueplus.core.biospecimen.domain.CollectionProtocolEvent;
import com.krishagni.catissueplus.core.biospecimen.domain.CollectionProtocolRegistration;
import com.krishagni.catissueplus.core.biospecimen.domain.Specimen;
import com.krishagni.catissueplus.core.biospecimen.domain.SpecimenRequirement;
import com.krishagni.catissueplus.core.biospecimen.domain.Visit;
import com.krishagni.catissueplus.core.biospecimen.domain.factory.CpErrorCode;
import com.krishagni.catissueplus.core.biospecimen.domain.factory.SpecimenErrorCode;
import com.krishagni.catissueplus.core.biospecimen.events.CollectionProtocolRegistrationDetail;
import com.krishagni.catissueplus.core.biospecimen.events.LabelPrintJobSummary;
import com.krishagni.catissueplus.core.biospecimen.events.ParticipantDetail;
import com.krishagni.catissueplus.core.biospecimen.events.PrintSpecimenLabelDetail;
import com.krishagni.catissueplus.core.biospecimen.events.SpecimenDetail;
import com.krishagni.catissueplus.core.biospecimen.events.VisitDetail;
import com.krishagni.catissueplus.core.biospecimen.repository.DaoFactory;
import com.krishagni.catissueplus.core.biospecimen.services.CollectionProtocolRegistrationService;
import com.krishagni.catissueplus.core.biospecimen.services.SpecimenService;
import com.krishagni.catissueplus.core.biospecimen.services.VisitService;
import com.krishagni.catissueplus.core.biospecimen.services.impl.DefaultSpecimenLabelPrinter;
import com.krishagni.catissueplus.core.common.OpenSpecimenAppCtxProvider;
import com.krishagni.catissueplus.core.common.PlusTransactional;
import com.krishagni.catissueplus.core.common.domain.LabelPrintJob;
import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import com.krishagni.catissueplus.core.common.events.RequestEvent;
import com.krishagni.catissueplus.core.common.events.ResponseEvent;
import com.krishagni.catissueplus.core.common.service.ConfigurationService;
import com.krishagni.catissueplus.core.common.service.LabelGenerator;
import com.krishagni.catissueplus.core.common.util.Status;
import com.krishagni.openspecimen.sgh.SghErrorCode;
import com.krishagni.openspecimen.sgh.events.BulkParticipantRegDetail;
import com.krishagni.openspecimen.sgh.events.BulkParticipantRegSummary;
import com.krishagni.openspecimen.sgh.services.CprService;
import com.krishagni.openspecimen.sgh.services.TridGenerator;

public class CprServiceImpl implements CprService {
	
	private DaoFactory daoFactory;
	
	private CollectionProtocolRegistrationService cprService;
	
	private SpecimenService specimenSvc;
	
	private VisitService visitService;
	
	private TridGenerator tridGenerator;
	
	private ConfigurationService cfgSvc;
	
	public void setDaoFactory(DaoFactory daoFactory) {
		this.daoFactory = daoFactory;
	}
	
	public void setCprService(CollectionProtocolRegistrationService cprService) {
		this.cprService = cprService;
	}
	
	public void setSpecimenSvc(SpecimenService specimenSvc) {
		this.specimenSvc = specimenSvc;
	}

	public void setVisitService(VisitService visitService) {
		this.visitService = visitService;
	}
	
	public void setTridGenerator(TridGenerator tridGenerator) {
		this.tridGenerator = tridGenerator;
	}
	
	public void setCfgSvc(ConfigurationService cfgSvc) {
		this.cfgSvc = cfgSvc;
	}

	@Override
	@PlusTransactional	
	public ResponseEvent<BulkParticipantRegDetail> registerParticipants(RequestEvent<BulkParticipantRegSummary> req) {		
		try {
			BulkParticipantRegSummary regReq = req.getPayload();
			int participantCount = regReq.getParticipantCount();
			String printerName = regReq.getPrinterName();
			
			if (participantCount < 1){
				return ResponseEvent.userError(SghErrorCode.INVALID_PARTICIPANT_COUNT);
			}
			
			CollectionProtocol cp = daoFactory.getCollectionProtocolDao().getById(regReq.getCpId());
			if (cp == null) {
				return ResponseEvent.userError(CpErrorCode.NOT_FOUND);
			}
			
			List<CollectionProtocolRegistrationDetail> registrations = new ArrayList<CollectionProtocolRegistrationDetail>();
			for (int i = 0; i < participantCount; i++){
				CollectionProtocolRegistrationDetail regDetail = registerParticipant(cp, regReq.isPrintLabels(), printerName);
				registrations.add(regDetail);
			}
			
			printTrids(registrations, printerName);
			
			return ResponseEvent.response(BulkParticipantRegDetail.from(regReq, registrations));			
		} catch (OpenSpecimenException ose) {
			return ResponseEvent.error(ose);			
		} catch (Exception e) {
			return ResponseEvent.serverError(e);			
		}
	}
	
	private CollectionProtocolRegistrationDetail registerParticipant(CollectionProtocol cp, Boolean isPrintLabels, String printerName) {
		CollectionProtocolRegistrationDetail cprDetail = getRegistrationDetail(cp);
		ResponseEvent<CollectionProtocolRegistrationDetail> regResp = cprService.createRegistration(getRequest(cprDetail));
		regResp.throwErrorIfUnsuccessful();
		
		cprDetail = regResp.getPayload();
		Set<CollectionProtocolEvent> eventColl = cp.getCollectionProtocolEvents();
		int visitCnt = 1;
		
		List<Long> specimenIds = new ArrayList<Long>();
		for (CollectionProtocolEvent cpe : eventColl) {
			VisitDetail visitDetail = createVisit(cprDetail, cpe, printerName, visitCnt++);
			ResponseEvent<VisitDetail> visitResp = visitService.addVisit(getRequest(visitDetail));
			visitResp.throwErrorIfUnsuccessful();
			
			visitDetail = visitResp.getPayload();
			Visit visit = getVisit(visitDetail, cpe);
			
			List<SpecimenRequirement> requirements = new ArrayList<SpecimenRequirement>(cpe.getTopLevelAnticipatedSpecimens());
			Collections.sort(requirements);
			
			for (SpecimenRequirement sr : requirements) {
				createSpecimens(sr, visit, null, specimenIds);
			}
		}
		
		if(isPrintLabels){
			RequestEvent<PrintSpecimenLabelDetail> printLabelsReq = getPrintLabelsReq(specimenIds);
			ResponseEvent<LabelPrintJobSummary> printLabelsResp = specimenSvc.printSpecimenLabels(printLabelsReq);
			printLabelsResp.throwErrorIfUnsuccessful();
		}
		return cprDetail;
	}


	private void createSpecimens(SpecimenRequirement sr, Visit visit, SpecimenDetail parent, List<Long> specimenIds) {
			Specimen specimen = sr.getSpecimen();
			specimen.setVisit(visit);
			if(parent != null){
				Specimen parentSpecimen = new Specimen();
				parentSpecimen.setId(parent.getId());
				parentSpecimen.setVisit(visit);
				specimen.setParentSpecimen(parentSpecimen);
			}
			
			SpecimenDetail spDetail = SpecimenDetail.from(specimen);
			spDetail.setStatus(Status.SPECIMEN_COLLECTION_STATUS_PENDING.getStatus());
			
			ResponseEvent<SpecimenDetail> specimenResp = specimenSvc.createSpecimen(getRequest(spDetail));
			specimenResp.throwErrorIfUnsuccessful();
			
			spDetail = specimenResp.getPayload();
			if (Specimen.ALIQUOT.equals(spDetail.getLineage())){
				specimenIds.add(spDetail.getId());
			}
			
			for (SpecimenRequirement childSr : sr.getOrderedChildRequirements()) {
				createSpecimens(childSr, visit, spDetail, specimenIds);
			}
	}

	private Visit getVisit(VisitDetail visitDetail, CollectionProtocolEvent cpe) {
		Visit visit = new Visit();
		visit.setName(visitDetail.getName());
		
		CollectionProtocolRegistration cpr = new CollectionProtocolRegistration();
		cpr.setPpid(visitDetail.getPpid());
		cpr.setId(visitDetail.getCprId());
		cpr.setCollectionProtocol(cpe.getCollectionProtocol());
		
		visit.setRegistration(cpr);
		return visit;
	}

	private CollectionProtocolRegistrationDetail getRegistrationDetail(CollectionProtocol cp) {
		CollectionProtocolRegistrationDetail cprDetail = new CollectionProtocolRegistrationDetail();
		cprDetail.setRegistrationDate(new Date());
		cprDetail.setCpId(cp.getId());
		cprDetail.setPpid(tridGenerator.getNextTrid());
		
		ParticipantDetail participant = new ParticipantDetail();
		cprDetail.setParticipant(participant);
		return cprDetail;
	}
	
	private VisitDetail createVisit(CollectionProtocolRegistrationDetail cprDetail,
			CollectionProtocolEvent cpe, String printerName, int visitCnt) {
		VisitDetail visit = new VisitDetail();
		visit.setEventId(cpe.getId());
		visit.setStatus(Status.VISIT_STATUS_PENDING.getStatus());
		visit.setSite(printerName);
		visit.setCprId(cprDetail.getId());
		visit.setPpid(cprDetail.getPpid());
		visit.setCpShortTitle(cpe.getCollectionProtocol().getShortTitle());
		visit.setName(cprDetail.getPpid() + "-v" + visitCnt);
		return visit;
	}
	
	private void printTrids(List<CollectionProtocolRegistrationDetail> registrations, String printerName) {
		List<String> trids = new ArrayList<String>();
		for (CollectionProtocolRegistrationDetail cprDetail : registrations) {
			trids.add(cprDetail.getPpid());
		}
		
		DefaultSpecimenLabelPrinter printer = getLabelPrinter();
		if (printer == null) {
			throw OpenSpecimenException.serverError(SpecimenErrorCode.NO_PRINTER_CONFIGURED);
		}
		
		List<Specimen> specimens = new ArrayList<Specimen>();
		for(String trid : trids){
			specimens.addAll(getSpecimensToPrint(trid, printerName));
		}
		
		LabelPrintJob job = printer.print(specimens, 1);
		if (job == null) {
			throw OpenSpecimenException.userError(SpecimenErrorCode.PRINT_ERROR);
		}
		
	}
	
	private Collection<Specimen> getSpecimensToPrint(String visitName, String printerName) {
		
		List<Specimen> specimens = new ArrayList<Specimen>();
		Visit visit = new Visit();
		visit.setName(visitName);
		Site site = new Site();
		site.setName(printerName);
		visit.setSite(site);
		
		for(int i = 0; i < 2; ++i){
			Specimen specimen = new Specimen();
			specimen.setVisit(visit);
			if(i==1){
				specimen.setLabel(visitName+" ");
			} else {
				specimen.setLabel(visitName);
			}
			
			specimens.add(specimen);
		}
		return specimens;
	}
	
	private RequestEvent<PrintSpecimenLabelDetail> getPrintLabelsReq(List<Long> specimenIds) {
		PrintSpecimenLabelDetail printLblDetail = new PrintSpecimenLabelDetail();
		printLblDetail.setSpecimenIds(specimenIds);
		return getRequest(printLblDetail);
	}
	
	private DefaultSpecimenLabelPrinter getLabelPrinter() {
		String labelPrinterBean = cfgSvc.getStrSetting(
				ConfigParams.MODULE, 
				ConfigParams.SPECIMEN_LABEL_PRINTER, 
				"defaultTridPrinter");
		return (DefaultSpecimenLabelPrinter)OpenSpecimenAppCtxProvider
				.getAppCtx()
				.getBean(labelPrinterBean);
	}
	
	private <T> RequestEvent<T> getRequest(T payload) {
		return new RequestEvent<T>(payload);
	}
}
