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

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vts.rcs.domain.constants.Constants;
import com.vts.rcs.domain.platform.MessageObject;
import com.vts.rcs.domain.platform.MisCurrDate;
import com.vts.rcs.domain.platform.Template;
import com.vts.rcs.domain.platform.VtBot;
import com.vts.rcs.domain.platform.VtUser;
import com.vts.rcs.domain.platform.VtUserSummaryReport;
import com.vts.rcs.domain.pushapi.dto.ClevertapMessageRequest;
import com.vts.rcs.domain.service.BotService;
import com.vts.rcs.domain.service.MisCurrDateService;
import com.vts.rcs.domain.service.TemplateService;
import com.vts.rcs.domain.service.UserService;
import com.vts.rcs.domain.service.VtUserSummaryService;
import com.vts.rcs.pushapi.domain.PushResponse;
import com.vts.rcs.pushapi.kafka.MessageProducer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@CrossOrigin
public class CleverTapMessageController {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Value("${push.api.instance.id}")
    private String instanceId;

    @Value("${push.api.kafka.topic}")
    public String topicName;

    @Autowired
    private MessageProducer producer;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MisCurrDateService misCurrDateService;

    @Autowired
    private UserService userService;

    @Autowired
    private BotService botService;

    @Autowired
    private TemplateService templateService;

    @Autowired
    private VtUserSummaryService vtUserSummaryService;

    @RequestMapping(value = "/clevertap/sendmessage", method = { RequestMethod.OPTIONS, RequestMethod.POST }, produces = { "application/json" })
    public String sendRcsMessage(@RequestBody ClevertapMessageRequest request, HttpServletResponse resp, HttpServletRequest req)
            throws JsonProcessingException {

        logger.info("Received request to /clevertap/sendmessage:\n{}", request);

        req.getHeaderNames().asIterator().forEachRemaining(
                name -> logger.info("Header: {} = {}", name, req.getHeader(name))
        );

        String clientIP = Optional.ofNullable(req.getHeader("X-Forwarded-For"))
                .orElse(Optional.ofNullable(req.getHeader("X-Real-IP")).orElse(req.getRemoteAddr()));
        logger.info("Client IP: {}", clientIP);

        List<PushResponse> responseList = new ArrayList<>();

        try {
            if (request.getRcsContent().getSenderId()== null || request.getRcsContent().getContent().getTemplateId() == null ||
                request.getTo() == null) {
                logger.warn("Missing required fields in request");
                PushResponse response = new PushResponse();
                response.setCode("-100");
                response.setDesc("Missing required fields");
                responseList.add(response);
                return objectMapper.writeValueAsString(responseList);
            }
            
            VtBot vtbot = botService.getBotDataByOperatorBotId(request.getRcsContent().getSenderId());
			if (vtbot == null) {
				PushResponse response = new PushResponse();
				logger.warn("Bot not found for bot_id: {}", request.getRcsContent().getSenderId());
				response.setCode("-102");
				response.setDesc("Bot not found.");
				responseList.add(response);
				return objectMapper.writeValueAsString(responseList);
			}

			VtUser vtUser = userService.getUserByNameFromDb(vtbot.getUserName());
			if (vtUser == null) {
                logger.warn("Invalid username: {}", vtbot.getUserName());
                PushResponse response = new PushResponse();
                response.setCode("-101");
                response.setDesc("Invalid username");
                responseList.add(response);
                return objectMapper.writeValueAsString(responseList);
            }

            logger.info("Processing RCS message for msisdn(s): {}", request.getTo());
            logger.info("userName: {}", vtUser.getUserName());
            
            String token = req.getHeader("authorization");
            logger.info("token {}", token);
            if (token == null || !token.equals(vtUser.getApiKey())) {
                logger.warn("Unauthorized access attempt for user: {}", vtUser.getUserName());
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return objectMapper.writeValueAsString(
                        Collections.singletonMap("error", "Unauthorized: Invalid API Key")
                );
            }

            String[] phoneNumbers = request.getTo().split(",");
            for (String rawNumber : phoneNumbers) {
                String number = rawNumber.trim().replaceFirst("^\\+", "");
                PushResponse response = new PushResponse();

                try {
                    logger.info("Processing number: {}", number);

                    String messageId = new ObjectId().toHexString();
                    String conversationId = MessageObject.generateConversationId(number, instanceId);
                    String currentDateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

                    MessageObject messageObject = buildRcsMessageObject(request, messageId, conversationId, vtUser);
                    if (messageObject == null) {
                        response.setCode("-108");
                        response.setDesc("Bot or template not found.");
                        responseList.add(response);
                        continue;
                    }

                    messageObject.setDestNumber(number);

                    MisCurrDate savedMisCurr = misCurrDateService.saveAllMessageObjectInMisCurrDate(messageObject, vtUser, messageId, null, null);
                    if (savedMisCurr != null) {
                        messageObject.setMisCurrDateId(savedMisCurr.getId().toString());
                        VtUserSummaryReport savedSummary = vtUserSummaryService.saveUserSummaryInDb(savedMisCurr, vtUser);
                        if (savedSummary != null) {
                            messageObject.setVtUserSummaryId(savedSummary.getId().toString());
                        }
                    }

                    String jsonMessage = objectMapper.writeValueAsString(messageObject);
                    producer.send(topicName, jsonMessage);

                    response.setMsgId(messageId);
                    response.setCode("200");
                    response.setDesc("RCS message queued successfully.");
                    response.setReceiveTime(currentDateTime);

                } catch (Exception e) {
                    logger.error("Exception while processing number: {}", number, e);
                    response.setCode("-500");
                    response.setDesc("Unexpected error occurred: " + e.getMessage());
                }

                responseList.add(response);
            }

            logger.info("Completed processing all numbers.");

        } catch (Exception e) {
            logger.error("Exception while processing RCS message", e);
            PushResponse response = new PushResponse();
            response.setCode("-500");
            response.setDesc("Unexpected error occurred: " + e.getMessage());
            responseList.add(response);
        }

        return objectMapper.writeValueAsString(responseList);
    }

    private MessageObject buildRcsMessageObject(ClevertapMessageRequest request, String messageId,
                                                String conversationId, VtUser user) throws JsonProcessingException {
        VtBot bot = null;
        Template template = null;

        try {
            template = templateService.getByTemplateName(request.getRcsContent().getContent().getTemplateId());
        } catch (Exception e) {
            logger.warn("Failed to fetch template: {}", request.getRcsContent().getContent().getTemplateId());
        }

        try {
            if (template != null) {
                bot = botService.getBotDataByBotName(template.getBotName());
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch bot for user: {}", user.getUserName());
        }

        if (bot == null || template == null) {
            return null;
        }
        
        Map<String, String> paramMap = new HashMap<>();
        Map<String, String> customParams = new HashMap<>();
        List<ClevertapMessageRequest.Parameter> variables = request.getRcsContent()
                .getContent()
                .getParameters();

        if (variables != null && !variables.isEmpty()) {
            for (ClevertapMessageRequest.Parameter param : variables) {
                if (param != null && param.getName() != null && param.getValue() != null && !param.getValue().isBlank()) {
                    paramMap.put(param.getName(), param.getValue());
                }
            }

            if (template.getCustomParams() != null && !template.getCustomParams().isEmpty()) {

                if (template.getCustomParams().contains("message_id")) {
                    customParams.put("message_id", messageId);
                }
                if (template.getCustomParams().contains("conversation_id")) {
                    customParams.put("conversation_id", conversationId);
                }
                if (template.getCustomParams().contains("PhoneNo")) {
                    customParams.put("PhoneNo", request.getTo());
                }

                for (String placeholder : template.getCustomParams()) {
                    if ("message_id".equals(placeholder) || "conversation_id".equals(placeholder) || "PhoneNo".equals(placeholder)) {
                        continue;
                    }
                    if (paramMap.containsKey(placeholder)) {
                        String value = paramMap.get(placeholder);
                        if (value != null && !value.isBlank()) {
                            customParams.put(placeholder, value);
                        }
                    }
                }
            }
        } else {
            customParams.put("message_id", messageId);
            customParams.put("conversation_id", conversationId);
            if (request.getTo() != null) {
                customParams.put("PhoneNo", request.getTo());
            }
        }

        MessageObject message = new MessageObject();
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
        message.setBotId(bot.getOperatorBotId());
        message.setVtBotId(bot.getId().toString());
        message.setUserId(user.getId().toString());
        message.setBotType(bot.getBotType());
        message.setBrandName(bot.getCreationData().getData().getBrandDetails().getBrandName());
        message.setCampaignId(new ObjectId().toHexString());
        message.setCampaignName("CleverTapApiCamp_" + new Timestamp(System.currentTimeMillis()));
        message.setCustRef(request.getMsgId() != null ? request.getMsgId() : "");

        String type = template.getType();
        if ("text_message".equalsIgnoreCase(type)) {
            message.setMessageType(template.getTextMessageContent() != null && template.getTextMessageContent().length() >= 160
                    ? Constants.MESSAGE_TYPE_RICH_STANDALONE : Constants.MESSAGE_TYPE_BASIC);
        } else if ("rich_card".equalsIgnoreCase(type)) {
            message.setMessageType(Constants.MESSAGE_TYPE_RICH_STANDALONE);
        } else if ("carousel".equalsIgnoreCase(type)) {
            message.setMessageType(Constants.MESSAGE_TYPE_RICH_CAROUSEL);
        }

        return message;
    }
}
