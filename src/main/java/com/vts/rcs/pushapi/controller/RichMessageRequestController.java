//package com.vts.rcs.pushapi.controller;
//
//import java.util.ArrayList;
//import java.util.Date;
//import java.util.List;
//import java.util.regex.Pattern;
//
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.RequestBody;
//import org.springframework.web.bind.annotation.RestController;
//
//import com.fasterxml.jackson.core.JsonProcessingException;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.vts.rcs.domain.constants.ErrorCodesEnum;
//import com.vts.rcs.domain.platform.MessageObject;
//import com.vts.rcs.pushapi.domain.ContentMessage;
//import com.vts.rcs.pushapi.domain.JsonRequest;
//import com.vts.rcs.pushapi.domain.PushRequest;
//import com.vts.rcs.pushapi.domain.PushResponse;
//import com.vts.rcs.pushapi.kafka.MessageProducer;
//
//import jakarta.servlet.http.HttpServletRequest;
//import jakarta.servlet.http.HttpServletResponse;
//
//@RestController
//public class RichMessageRequestController {
//
//	private final Logger logger = LoggerFactory.getLogger(getClass());
//
//	@Value("${push.api.instance.id}")
//	private String instanceId;
//
//	@Value("${push.api.kafka.topic.rich}")
//	private String TopicName;
//
//	@Autowired
//	private MessageProducer producer;
//
//	@Autowired
//	private ObjectMapper objectMapper;
//
//	@PostMapping("/json/rich")
//	public String sendRichMsg(@RequestBody JsonRequest jsonRequest, HttpServletResponse resp, HttpServletRequest req)
//			throws JsonProcessingException {
//
//		String phoneNumber = jsonRequest.getPhoneNumber();
//		String botId = jsonRequest.getBotId();
//		String messageId = jsonRequest.getMessageId();
//		boolean sendGipLink = jsonRequest.isSendGipLink();
//		ContentMessage contentMessage = jsonRequest.getContentMessage();
//		ArrayList<PushResponse> responseList = new ArrayList<>();
//
//		logger.info("JSON BULK SMS ENTERING");
//
//		// Retrieve client IP address
//		String clientIP = req.getHeader("X-Forwarded-For");
//		if (clientIP == null || clientIP.isEmpty()) {
//			clientIP = req.getHeader("X-Real-IP");
//		}
//		if (clientIP == null || clientIP.isEmpty()) {
//			clientIP = req.getRemoteAddr();
//		}
//
//		// Process phone numbers
//		if (phoneNumber.contains(",")) {
//			String[] mNumberArray = phoneNumber.split(",");
//			for (String mNumber : mNumberArray) {
//				if (mNumber.startsWith("+")) {
//					mNumber = mNumber.substring(1);
//				}
//				PushRequest request = new PushRequest(mNumber, botId, messageId, sendGipLink, sendGipLink,
//						contentMessage);
//				PushResponse response = new PushResponse();
//				boolean validateRequest = validateRequest(request, response);
//
//				if (validateRequest) {
//					processRequest(request, response);
//				}
//				responseList.add(response);
//			}
//		} else {
//			String mNumber = phoneNumber;
//			if (mNumber.startsWith("+")) {
//				mNumber = mNumber.substring(1);
//			}
//			PushRequest request = new PushRequest(mNumber, botId, messageId, sendGipLink, sendGipLink, contentMessage);
//			PushResponse response = new PushResponse();
//
//			boolean validateRequest = validateRequest(request, response);
//
//			if (validateRequest) {
//				processRequest(request, response);
//			}
//			responseList.add(response);
//		}
//
//		return objectMapper.writeValueAsString(responseList);
//	}
//
//	private boolean validateRequest(PushRequest request, PushResponse response) {
//
//		if (request.getPhoneNumber() == null
//				|| (request.getPhoneNumber().length() == 12 && !request.getPhoneNumber().startsWith("91"))
//				|| !Pattern.compile("\\d+").matcher(request.getPhoneNumber()).matches()
//				|| (request.getPhoneNumber().length() == 11 && !request.getPhoneNumber().startsWith("0"))
//				|| (request.getPhoneNumber().length() == 10 && request.getPhoneNumber().startsWith("1"))
//				|| (request.getPhoneNumber().length() == 10 && request.getPhoneNumber().startsWith("2"))
//				|| (request.getPhoneNumber().length() == 10 && request.getPhoneNumber().startsWith("3"))
//				|| (request.getPhoneNumber().length() == 10 && request.getPhoneNumber().startsWith("4"))
//				|| (request.getPhoneNumber().length() == 10 && request.getPhoneNumber().startsWith("5"))) {
//			response.setCode(ErrorCodesEnum.INVALID_DESTINATION_NUMBER.getErrorCode());
//			response.setDesc(ErrorCodesEnum.INVALID_DESTINATION_NUMBER.getErrorDesc());
//			return false;
//		}
//
//		if (request.getPhoneNumber() == null || request.getPhoneNumber().length() < 10
//				|| request.getPhoneNumber().trim().length() > 12
//				|| !Pattern.compile("\\d+").matcher(request.getPhoneNumber()).matches()) {
//			response.setCode(ErrorCodesEnum.INVALID_DESTINATION_NUMBER.getErrorCode());
//			response.setDesc(ErrorCodesEnum.INVALID_DESTINATION_NUMBER.getErrorDesc());
//			return false;
//		}
//
//		if (request.getContentMessage().getText() == null || request.getContentMessage().getText().length() == 0) {
//			response.setCode(ErrorCodesEnum.INVALID_MESSAGE_TEXT.getErrorCode());
//			response.setDesc(ErrorCodesEnum.INVALID_MESSAGE_TEXT.getErrorDesc());
//			return false;
//		}
//
//		return true;
//
//	}
//
//	private void processRequest(PushRequest request, PushResponse response) {
//		try {
//			String msgId = request.getMessageId();
//			String messageId;
//			if (msgId != null) {
//				messageId = response.setMsgId(msgId);
//			} else {
//				messageId = MessageObject.generateMessageId(request.getPhoneNumber(), instanceId);
//			}
//			String message = generateJsonMessage(request, messageId, response);
//			producer.send(TopicName, message);
//
//			response.setMsgId(messageId);
//			response.setCode("6001");
//			response.setDesc("Message received by platform.");
//		} catch (Exception e) {
//			response.setCode("-10");
//			response.setDesc("Internal error occured.");
//			response.setMsgId(null);
//		}
//	}
//
//	private String generateJsonMessage(PushRequest request, String messageId, PushResponse response)
//			throws JsonProcessingException {
//		String mNumber = request.getPhoneNumber();
//		MessageObject msgObject = new MessageObject();
//		msgObject.setMessage(request.getContentMessage().getText());
//		msgObject.setMessageId(messageId);
//		msgObject.setDestNumber(mNumber);
//		msgObject.setSplitCount(1);
//		msgObject.setCustomParams(request.getContentMessage().getCustomParams());
//		if (request != null && request.getContentMessage() != null) {
//			ContentMessage contentMessage = request.getContentMessage();
//
//			msgObject.setMessage(contentMessage.getText());
//			msgObject.setTemplateCode(contentMessage.getTemplateCode());
//			msgObject.setCustomParams(contentMessage.getCustomParams());
//			msgObject.setFileName(contentMessage.getFileName());
//
//			// Map ContentInfo if available
//			if (contentMessage.getContentInfo() != null) {
//				MessageObject.ContentInfo contentInfo = new MessageObject.ContentInfo();
//				contentInfo.setFileUrl(contentMessage.getContentInfo().getFileUrl());
//				msgObject.setContentInfo(contentInfo);
//			}
//
//			// Map Suggestions if available
//			if (contentMessage.getSuggestions() != null) {
//				List<MessageObject.Suggestion> suggestions = new ArrayList<>();
//				for (ContentMessage.Suggestion suggestion : contentMessage.getSuggestions()) {
//					MessageObject.Suggestion mappedSuggestion = new MessageObject.Suggestion();
//
//					// Map Reply if available
//					if (suggestion.getReply() != null) {
//						MessageObject.Reply reply = new MessageObject.Reply();
//						reply.setText(suggestion.getReply().getText());
//						reply.setPostbackData(suggestion.getReply().getPostbackData());
//						mappedSuggestion.setReply(reply);
//					}
//
//					// Map Action if available
//					if (suggestion.getAction() != null) {
//						MessageObject.Action action = new MessageObject.Action();
//						action.setText(suggestion.getAction().getText());
//						action.setPostbackData(suggestion.getAction().getPostbackData());
//
//						// Map OpenUrlAction
//						if (suggestion.getAction().getOpenUrlAction() != null) {
//							MessageObject.OpenUrlAction openUrlAction = new MessageObject.OpenUrlAction();
//							openUrlAction.setUrl(suggestion.getAction().getOpenUrlAction().getUrl());
//							action.setOpenUrlAction(openUrlAction);
//						}
//
//						// Map ViewLocationAction
//						if (suggestion.getAction().getViewLocationAction() != null) {
//							MessageObject.ViewLocationAction viewLocationAction = new MessageObject.ViewLocationAction();
//							ContentMessage.LatLong latLong = suggestion.getAction().getViewLocationAction()
//									.getLatLong();
//							if (latLong != null) {
//								MessageObject.LatLong mappedLatLong = new MessageObject.LatLong();
//								mappedLatLong.setLatitude(latLong.getLatitude());
//								mappedLatLong.setLongitude(latLong.getLongitude());
//								viewLocationAction.setLatLong(mappedLatLong);
//							}
//							viewLocationAction.setLabel(suggestion.getAction().getViewLocationAction().getLabel());
//							action.setViewLocationAction(viewLocationAction);
//						}
//
//						// Map ShareLocationAction
//						if (suggestion.getAction().getShareLocationAction() != null) {
//							MessageObject.ShareLocationAction shareLocationAction = new MessageObject.ShareLocationAction();
//							action.setShareLocationAction(shareLocationAction);
//						}
//
//						mappedSuggestion.setAction(action);
//					}
//
//					suggestions.add(mappedSuggestion);
//				}
//				msgObject.setSuggestions(suggestions);
//			}
//		}
//
//		int messageLength = request.getContentMessage().getText().length();
//
//		msgObject.setReceiveTime(new java.sql.Timestamp(new Date().getTime()));
//
//		response.setTime(msgObject.getReceiveTime().toString());
//
//		String requestId = messageId;
//		msgObject.setUniqueId(messageId);
//		msgObject.setUsername(request.getBotId());
//		List<String> partMessageIds = new ArrayList<>();
//		partMessageIds.add(messageId);
//
//		return objectMapper.writeValueAsString(msgObject);
//	}
//
//}
