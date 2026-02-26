package com.vts.rcs.pushapi.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vts.rcs.domain.constants.Constants;
import com.vts.rcs.domain.moengage.dto.MoEngageMessageRequest;
import com.vts.rcs.domain.platform.*;
import com.vts.rcs.domain.repository.MoEngageRequestRepository;
import com.vts.rcs.domain.service.*;
import com.vts.rcs.pushapi.domain.PushResponse;
import com.vts.rcs.pushapi.kafka.MessageProducer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@CrossOrigin
public class MoEngageMessageController {

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

	@Autowired
	MoEngageRequestService moEngageRequestService;

	@RequestMapping(value = "/moengage/sendmessage", method = { RequestMethod.OPTIONS,
			RequestMethod.POST }, produces = { "application/json" })
	public String sendMoEngageMsg(@RequestBody MoEngageMessageRequest request, HttpServletResponse resp,
			HttpServletRequest req) throws JsonProcessingException {

		logger.info(" Received request to /moengage/sendmessage");

		req.getHeaderNames().asIterator()
				.forEachRemaining(name -> logger.info("Header: {} = {}", name, req.getHeader(name)));

		String clientIP = Optional.ofNullable(req.getHeader("X-Forwarded-For"))
				.orElse(Optional.ofNullable(req.getHeader("X-Real-IP")).orElse(req.getRemoteAddr()));
		logger.info("Client IP: {}", clientIP);

		List<PushResponse> responseList = new ArrayList<>();

		for (MoEngageMessageRequest.Message msg : request.getMessages()) {
			String number = msg.getDestination().trim().replaceFirst("^\\+", "");
			PushResponse response = new PushResponse();

			try {
				logger.info(" Processing number: {}", number);

				VtBot vtbot = botService.getBotDataByOperatorBotId(msg.getRcs().getBot_id());
				if (vtbot == null) {
					logger.warn("Bot not found for bot_id: {}", msg.getRcs().getBot_id());
					response.setCode("-102");
					response.setDesc("Bot not found.");
					responseList.add(response);
					continue;
				}

				VtUser user = userService.getUserByNameFromDb(vtbot.getUserName());
				if (user == null) {
					logger.warn("Invalid user for bot Name : {}", msg.getRcs().getBot_id());
					response.setCode("-101");
					response.setDesc("User not found.");
					responseList.add(response);
					continue;
				}

				String token = req.getHeader("authorization");
				logger.info("token {}", token);
				if (token == null || !token.equals(user.getApiKey())) {
					logger.warn("Unauthorized access attempt for user: {}", user.getUserName());
					resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
					return objectMapper
							.writeValueAsString(Collections.singletonMap("error", "Unauthorized: Invalid API Key"));
				}
				String currentDateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

				MoEngageRequest entity = moEngageRequestService.saveRequestInDB(request);
				ObjectId requestId = entity.getId();

				String messageId = new ObjectId().toHexString();
				String conversationId = MessageObject.generateConversationId(number, instanceId);

				MessageObject messageObject = buildMoengageMessageObject(msg, messageId, conversationId, user);

				if (messageObject == null) {
					response.setCode("-108");
					response.setDesc("Bot or template not found.");
					responseList.add(response);
					continue;
				}

				MisCurrDate savedMisCurr = misCurrDateService.saveAllMessageObjectInMisCurrDate(messageObject, user,
						messageId, null, requestId);
				if (savedMisCurr != null) {
					logger.info("Curr Date : {} ", savedMisCurr);
					messageObject.setMisCurrDateId(savedMisCurr.getId().toString());
					VtUserSummaryReport savedSummary = vtUserSummaryService.saveUserSummaryInDb(savedMisCurr, user);
					if (savedSummary != null) {
						messageObject.setVtUserSummaryId(savedSummary.getId().toString());
					}
				}

				String jsonMessage = objectMapper.writeValueAsString(messageObject);
				producer.send(TopicName, jsonMessage);

				response.setMsgId(messageId);
				response.setCode("6001");
				response.setDesc("Message queued successfully.");
				response.setReceiveTime(currentDateTime);

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

	private MessageObject buildMoengageMessageObject(MoEngageMessageRequest.Message msg, String messageId,
			String conversationId, VtUser user) throws JsonProcessingException {
		VtBot bot = null;
		Template template = null;

		try {
			bot = botService.getBotDataByOperatorBotId(user.getUserName(), msg.getRcs().getBot_id());
			if (bot == null) {
				// fallback if bot not found with username
				bot = botService.getBotDataByOperatorBotId(msg.getRcs().getBot_id());
			}

		} catch (Exception e) {
			logger.warn("Failed to fetch bot: {}", msg.getRcs().getBot_id());
		}

		try {
			template = templateService.getByTemplateName(msg.getRcs().getTemplate_id());
		} catch (Exception e) {
			logger.warn("Failed to fetch template: {}", msg.getRcs().getTemplate_id());
		}

		if (bot == null || template == null)
			return null;

		Map<String, String> paramMap = msg.getRcs().getMessage_content().getData().getParameters();
		Map<String, String> customParams = new HashMap<>();

		if (template.getCustomParams() != null) {
			if (template.getCustomParams().contains("message_id")) {
				customParams.put("message_id", messageId);
			}
			if (template.getCustomParams().contains("conversation_id")) {
				customParams.put("conversation_id", conversationId);
			}
			List<String> templatePlaceholders = template.getCustomParams();

			if (paramMap != null) {
				for (String templateCustom : templatePlaceholders) {
					if (paramMap.containsKey(templateCustom)) {
						String value = paramMap.get(templateCustom);
						if (value != null && !value.isBlank()) {
							customParams.put(templateCustom, value);
						}
					}
				}
			}
		}

		MessageObject message = new MessageObject();
		message.setMessage(msg.getRcs().getMessage_content().getData() != null
				&& msg.getRcs().getMessage_content().getData().getText() != null
						? msg.getRcs().getMessage_content().getData().getText()
						: template.getTextMessageContent());
		message.setTemplateCode(template.getName());
		message.setTemplateId(template.getId().toString());
		message.setCustomParams(objectMapper.writeValueAsString(customParams));
		message.setMessageId(messageId);
		message.setUniqueId(messageId);
		message.setRouteId(1);
		message.setSplitCount(1);
		message.setUsername(user.getUserName());
		message.setConversationId(conversationId);
		message.setReceiveTime(new Timestamp(System.currentTimeMillis()));
		message.setBotName(bot.getBotName());
		message.setDestNumber(msg.getDestination().replaceFirst("^\\+", ""));
		message.setBotId(bot.getOperatorBotId());
		message.setVtBotId(bot.getId().toString());
		message.setUserId(user.getId().toString());
		message.setBotType(bot.getBotType());
		message.setBrandName(bot.getCreationData().getData().getBrandDetails().getBrandName());
		message.setCampaignId(new ObjectId().toHexString());
		message.setCampaignName("MoengageApiCamp_" + new Timestamp(System.currentTimeMillis()));
		if (msg.getSms() != null) {
			message.setFileName(msg.getSms().getMessage());
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

		return message;
	}
}
