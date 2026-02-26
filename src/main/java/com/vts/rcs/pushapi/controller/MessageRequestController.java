package com.vts.rcs.pushapi.controller;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vts.rcs.domain.constants.Constants;
import com.vts.rcs.domain.platform.MessageObject;
import com.vts.rcs.domain.platform.MisCurrDate;
import com.vts.rcs.domain.platform.Template;
import com.vts.rcs.domain.platform.VtBot;
import com.vts.rcs.domain.platform.VtBot.OperatorData;
import com.vts.rcs.domain.platform.VtUser;
import com.vts.rcs.domain.platform.VtUserSummaryReport;
import com.vts.rcs.domain.service.BotService;
import com.vts.rcs.domain.service.MisCurrDateService;
import com.vts.rcs.domain.service.TemplateService;
import com.vts.rcs.domain.service.UserService;
import com.vts.rcs.domain.service.VtUserSummaryService;
import com.vts.rcs.pushapi.domain.ContentMessage;
import com.vts.rcs.pushapi.domain.ContentMessage.TemplateMessage;
import com.vts.rcs.pushapi.domain.JsonRequest;
import com.vts.rcs.pushapi.domain.PushRequest;
import com.vts.rcs.pushapi.domain.PushResponse;
import com.vts.rcs.pushapi.kafka.MessageProducer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@CrossOrigin
public class MessageRequestController {

	private final Logger logger = LoggerFactory.getLogger(getClass());

	@Value("${push.api.instance.id}")
	private String instanceId;

	@Value("${push.api.kafka.topic}")
	public String TopicName;

	@Autowired
	MessageProducer producer;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	MisCurrDateService misCurrDateService;

	@Autowired
	UserService userService;

	@Autowired
	BotService botService;

	@Autowired
	TemplateService templateService;

	@Autowired
	VtUserSummaryService vtUserSummaryService;

	@RequestMapping(value = "/apiservice/sendMsg", method = RequestMethod.GET, produces = "application/json")
	public String sendTextMsgGet(@RequestParam("userName") String userName,
			@RequestParam("phoneNumber") String phoneNumber, @RequestParam("templateCode") String templateCode,
			@RequestParam(value = "type", required = false) String type,
			@RequestParam(value = "botId", required = false) String botId,
			@RequestParam(value = "custRef", required = false) String custRef,
			@RequestParam(value = "customParams", required = false) String customParams, HttpServletResponse resp,
			HttpServletRequest req) throws JsonProcessingException {

		logger.info("Received request to /apiservice/sendMsg");
		
//		req.getHeaderNames().asIterator().forEachRemaining(
//		        name -> logger.info("Header: {} = {}", name, req.getHeader(name))
//		    );

		// Get client IP
		String clientIP = Optional.ofNullable(req.getHeader("X-Forwarded-For"))
				.orElse(Optional.ofNullable(req.getHeader("X-Real-IP")).orElse(req.getRemoteAddr()));
		logger.info("Client IP: {}", clientIP);

		List<PushResponse> responseList = new ArrayList<>();

		// Step 1: Validate mandatory fields
		if (isNullOrEmpty(userName) || isNullOrEmpty(phoneNumber) || isNullOrEmpty(templateCode)) {
			logger.warn("Mandatory fields missing: username, phoneNumber, or templateCode");
			PushResponse response = new PushResponse();
			response.setCode("-100");
			response.setDesc("Missing mandatory fields: username, phoneNumber, or templateCode.");
			responseList.add(response);
			return objectMapper.writeValueAsString(responseList);
		}

		VtUser user = userService.getUserByNameFromDb(userName);
		if (user == null) {
			logger.warn("❗ Invalid user: {}", userName);
		}
		
//		String token = req.getHeader("authorization");
//        logger.info("token {}", token);
//        if (token == null || !token.equals(user.getApiKey())) {
//            logger.warn("Unauthorized access attempt for user: {}", user.getUserName());
//            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//            return objectMapper.writeValueAsString(
//                    Collections.singletonMap("error", "Unauthorized: Invalid API Key")
//            );
//        }

		String[] phoneNumbers = phoneNumber.split(",");

		for (String rawNumber : phoneNumbers) {
			String number = rawNumber.trim().replaceFirst("^\\+", "");
			PushResponse response = new PushResponse();

			try {
				logger.info("Processing number: {}", number);

				ContentMessage contentMessage = new ContentMessage();
				contentMessage.setTemplateCode(templateCode);

				if (!isNullOrEmpty(customParams)) {
					TemplateMessage templateMessage = new TemplateMessage();
					templateMessage.setCustomParams(customParams); 
					contentMessage.setTemplateMessage(templateMessage);
				}

				PushRequest request = new PushRequest(number, type, botId, user, contentMessage, custRef);

				if (!validateRequest(request, response, user)) {
					logger.warn("Validation failed for number: {} | Reason: {}", number, response.getDesc());
					responseList.add(response);
					continue;
				}

				processRequest(request, response, user);
				logger.info("Successfully processed number: {}", number);

			} catch (Exception e) {
				logger.error("Exception while processing number: {}", number, e);
				response.setCode("-500");
				response.setDesc("Unexpected error occurred: " + e.getMessage());
			}

			responseList.add(response);
		}

		logger.info("Completed processing all numbers.");
		return objectMapper.writeValueAsString(responseList);
	}

	@RequestMapping(value = "/apiservice/sendmessage", method = { RequestMethod.OPTIONS,
			RequestMethod.POST }, produces = { "application/json" })
	public String sendTextMsg(@RequestBody JsonRequest jsonRequest, HttpServletResponse resp, HttpServletRequest req)
			throws JsonProcessingException {

		logger.info(" Received request to /json/message");
		
		req.getHeaderNames().asIterator().forEachRemaining(
		        name -> logger.info("Header: {} = {}", name, req.getHeader(name))
		    );

		String clientIP = Optional.ofNullable(req.getHeader("X-Forwarded-For"))
				.orElse(Optional.ofNullable(req.getHeader("X-Real-IP")).orElse(req.getRemoteAddr()));
		logger.info("Client IP: {}", clientIP);

		List<PushResponse> responseList = new ArrayList<>();

		// Step 1: Validate Mandatory Fields
		if (isNullOrEmpty(jsonRequest.getUserName()) || isNullOrEmpty(jsonRequest.getPhoneNumber())
				|| jsonRequest.getContentMessage() == null
				|| isNullOrEmpty(jsonRequest.getContentMessage().getTemplateCode())) {

			logger.warn("Mandatory fields missing: username, phoneNumber, or templateCode");
			PushResponse response = new PushResponse();
			response.setCode("-100");
			response.setDesc("Missing mandatory fields: username, phoneNumber, or templateCode.");
			responseList.add(response);
			return objectMapper.writeValueAsString(responseList);
		}

		VtUser user = userService.getUserByNameFromDb(jsonRequest.getUserName());
		if (user == null) {
			logger.warn("❗ Invalid user: {}", jsonRequest.getUserName());
		}
		
//		String token = req.getHeader("authorization");
//        logger.info("token {}", token);
//        if (token == null || !token.equals(user.getApiKey())) {
//            logger.warn("Unauthorized access attempt for user: {}", user.getUserName());
//            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//            return objectMapper.writeValueAsString(
//                    Collections.singletonMap("error", "Unauthorized: Invalid API Key")
//            );
//        }

		String[] phoneNumbers = jsonRequest.getPhoneNumber().split(",");

		for (String rawNumber : phoneNumbers) {
			String number = rawNumber.trim().replaceFirst("^\\+", "");
			PushResponse response = new PushResponse();

			try {
				logger.info(" Processing number: {}", number);

				PushRequest request = new PushRequest(number, jsonRequest.getType(), jsonRequest.getBotId(), user,
						jsonRequest.getContentMessage(), jsonRequest.getCustRef());

				if (!validateRequest(request, response, user)) {
					logger.warn(" Validation failed for number: {} | Reason: {}", number, response.getDesc());
					responseList.add(response);
					continue;
				}

				processRequest(request, response, user);
				logger.info(" Successfully processed number: {}", number);

			} catch (Exception e) {
				logger.error(" Exception while processing number: {}", number, e);
				response.setCode("-500");
				response.setDesc("Unexpected error occurred: " + e.getMessage());
			}

			responseList.add(response);
		}

		logger.info(" Completed processing all numbers.");
		return objectMapper.writeValueAsString(responseList);
	}

	private boolean validateRequest(PushRequest request, PushResponse response, VtUser user) {
		String phone = request.getPhoneNumber();

		if (user == null) {
			response.setCode("-101");
			response.setDesc("User not found.");
			return false;
		}

		if (isNullOrEmpty(phone)) {
			response.setCode("-102");
			response.setDesc("Phone number is empty.");
			return false;
		}

		if (!Pattern.matches("\\d{10,12}", phone) || (phone.length() == 12 && !phone.startsWith("91"))
				|| (phone.length() == 11 && !phone.startsWith("0"))
				|| (phone.length() == 10 && Pattern.matches("^[1-5].*", phone))) {
			response.setCode("-103");
			response.setDesc("Invalid phone number format.");
			return false;
		}

		Template template = templateService.getByTemplateName(request.getContentMessage().getTemplateCode());
		if (template == null) {
			response.setCode("-108");
			response.setDesc("template not found.");
			return false;
		}

		ContentMessage content = request.getContentMessage();
		if (content == null || isNullOrEmpty(content.getTemplateCode())) {
			response.setCode("-106");
			response.setDesc("Missing message text or template code.");
			return false;
		}

		return true;
	}

	private void processRequest(PushRequest request, PushResponse response, VtUser user) {
		try {
			String messageId = new ObjectId().toHexString();
			String conversationId = MessageObject.generateConversationId(request.getPhoneNumber(), instanceId);

			StringBuilder smsMessageText = new StringBuilder();
			StringBuilder whatsappMessageBody = new StringBuilder();

			MessageObject messageObject = generateJsonMessage(request, messageId, response, conversationId, user,
					smsMessageText, whatsappMessageBody);

			String smsMessage = null;
			String whatsappMessage = null;
			if (smsMessageText != null && !smsMessageText.isEmpty()) {
				logger.info("SMS MESSAGE 1 : {} ", smsMessageText);
				smsMessage = smsMessageText.toString();
				logger.info("SMS MESSAGE 2 : {} ", smsMessage);
			}
			if (whatsappMessageBody != null && !whatsappMessageBody.isEmpty()) {
				whatsappMessage = whatsappMessageBody.toString();

			}

			if (messageObject == null)
				return; // response is already set inside generateJsonMessage

			Template template = templateService.getByTemplateName(request.getContentMessage().getTemplateCode());
			MisCurrDate savedMisCurr = misCurrDateService.saveAllMessageObjectInMisCurrDate(messageObject, user,
					messageId, null, smsMessage, template.getSmsApiEndpoint(), whatsappMessage,
					template.getWhatsappApiEndpoint());

			if (savedMisCurr != null) {
				messageObject.setMisCurrDateId(savedMisCurr.getId().toString());
				VtUserSummaryReport savedSummary = vtUserSummaryService.saveUserSummaryInDb(savedMisCurr, user);
				if (savedSummary != null) {
					messageObject.setVtUserSummaryId(savedSummary.getId().toString());
				}
			}

			String jsonMessage = objectMapper.writeValueAsString(messageObject);
			producer.send(TopicName, jsonMessage);
			String currentDateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

			response.setMsgId(messageId);
			response.setCode("6001");
			response.setDesc("Message queued successfully.");
			response.setReceiveTime(currentDateTime);

		} catch (Exception e) {
			logger.error("Error while processing message: ", e);
			response.setCode("-107");
			response.setDesc("Processing failed: " + e.getMessage());
		}
	}

	private MessageObject generateJsonMessage(PushRequest request, String messageId, PushResponse response,
			String conversationId, VtUser user, StringBuilder smsMessageText, StringBuilder whatsappBodyText)
			throws JsonProcessingException {

		VtBot bot = null;
		Template template = null;
		String requestCustomParam = null;
		if (request.getContentMessage().getTemplateMessage() != null) {
			requestCustomParam = request.getContentMessage().getTemplateMessage().getCustomParams();
		}
		Map<String, String> paramMap = new HashMap<>();

		if (requestCustomParam != null && requestCustomParam.length() > 0) {
			try {
				if (requestCustomParam != null && !requestCustomParam.isEmpty()) {
					paramMap = objectMapper.readValue(requestCustomParam, new TypeReference<Map<String, String>>() {
					});
				}
			} catch (Exception e) {
				logger.info("Exception occurs when parse the custom param string to map : {} ", requestCustomParam);
				e.printStackTrace();
			}
		}

		try {
			bot = botService.getBotByUserNameAndBotName(user.getUserName(), request.getBotId());

		} catch (Exception e) {
			logger.warn("Failed to fetch bot for Name : {}", request.getBotId());
		}

		try {
			template = templateService.getByTemplateName(request.getContentMessage().getTemplateCode());
		} catch (Exception e) {
			logger.warn("Failed to fetch template: {}", request.getContentMessage().getTemplateCode());
		}

		if (bot == null || template == null) {
			response.setCode("-108");
			response.setDesc("Bot or template not found.");
			return null;
		}

		// Build message object
		MessageObject message = new MessageObject();
		message.setMessage(request.getContentMessage().getText());
		message.setTemplateCode(template.getName());
		message.setTemplateId(template.getId().toString());
		message.setFileName(request.getContentMessage().getFileName());
		if (bot != null) {
			ArrayList<OperatorData> operatorDataList = bot.getOperatorData();
			if (operatorDataList != null) {
				for (OperatorData operator : operatorDataList) {
					if (operator.getOperatorName().equalsIgnoreCase("VTL")) {
						message.setClientId(operator.getOperatorBotId());
						message.setClientSecret(operator.getOperatorBotSecret());
						break;
					}
				}
			}

		}
		String type = template.getType();
		if ("text_message".equalsIgnoreCase(type)) {
			message.setMessageType(
					template.getTextMessageContent() != null && template.getTextMessageContent().length() >= 160
							? Constants.MESSAGE_TYPE_RICH_STANDALONE
							: Constants.MESSAGE_TYPE_BASIC);
		} else if ("rich_card".equalsIgnoreCase(type)) {
			message.setMessageType(Constants.MESSAGE_TYPE_RICH_STANDALONE);
		} else if ("carousel".equalsIgnoreCase(type)) {
			message.setMessageType(Constants.MESSAGE_TYPE_RICH_CAROUSEL);
		}

		Map<String, String> customParams = new HashMap<>();
		if (template.getCustomParams() != null) {
			if (template.getCustomParams().contains("message_id")) {
				customParams.put("message_id", messageId);
			}
			if (template.getCustomParams().contains("conversation_id")) {
				customParams.put("conversation_id", conversationId);
			}
			if (template.getCustomParams().contains("PhoneNo")) {
				customParams.put("PhoneNo", request.getPhoneNumber());
			}

			List<String> templatePlaceholders = template.getCustomParams();

			for (String templateCustom : templatePlaceholders) {
				if (paramMap.containsKey(templateCustom)) {
					String value = paramMap.get(templateCustom);
					if (value != null && !value.isBlank()) {
						customParams.put(templateCustom, value);
					}
				}
			}

		}

		if (template.getIsFallbackSms() != null && template.getIsFallbackSms().equalsIgnoreCase("Y")) {
			String text = template.getFallbackSms();
//			if (text.contains(actualVariableName)) {
//				if (!variableColumnMap.getValueType().equalsIgnoreCase("static"))
//					text.replace(actualVariableName,
//							dynamicParamDto.getExcelColumnNameValueMap().get(variableColumnMap.getValue()));
//				else
//					text.replace(actualVariableName, variableColumnMap.getValue());
//			}
			smsMessageText.append(text);
		}

		if (template.getIsFallbackWhatsapp() != null && template.getIsFallbackWhatsapp().equalsIgnoreCase("Y")) {
			String text = template.getFallbackWhatsapp();
//			if (text.contains(actualVariableName)) {
//				if (!variableColumnMap.getValueType().equalsIgnoreCase("static"))
//					text.replace(actualVariableName,
//							dynamicParamDto.getExcelColumnNameValueMap().get(variableColumnMap.getValue()));
//				else
//					text.replace(actualVariableName, variableColumnMap.getValue());
//			}
			whatsappBodyText.append(text);

		}

		message.setCustomParams(objectMapper.writeValueAsString(customParams));
		message.setMessageId(messageId);
		message.setUniqueId(messageId);
		message.setRouteId(1);
		message.setSplitCount(1);
		message.setUsername(user.getUserName());
		message.setConversationId(conversationId);
		message.setReceiveTime(new Timestamp(System.currentTimeMillis()));
		message.setBotName(bot.getBotName());
		message.setDestNumber(request.getPhoneNumber());
		message.setBotId(bot.getOperatorBotId());
		message.setVtBotId(bot.getId().toString());
		message.setUserId(user.getId().toString());
		message.setBotType(bot.getBotType());
		message.setBrandName(bot.getCreationData().getData().getBrandDetails().getBrandName());
		message.setCampaignId(new ObjectId().toHexString());
		message.setCampaignName("TestingCamp_" + new Timestamp(System.currentTimeMillis()));
		if (request.getCustRef() != null) {
			message.setCustRef(request.getCustRef());
		}

		return message;
	}

	private boolean isNullOrEmpty(String str) {
		return str == null || str.trim().isEmpty();
	}

}
