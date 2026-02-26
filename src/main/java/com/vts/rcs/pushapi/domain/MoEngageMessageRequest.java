package com.vts.rcs.pushapi.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MoEngageMessageRequest {

    private List<Message> messages;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String destination;
        private String callback_data;
        private List<String> fallback_order;
        private Rcs rcs;
        private Sms sms;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Rcs {
        private String bot_id;
        private String template_id;
        private MessageContent message_content;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageContent {
        private String type;
        private MessageData data;
        private List<Suggestion> suggestions;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageData {
        private String text;
        private Map<String, String> parameters;

        private Media media;
        private String title;
        private String description;
        private String orientation;
        private String height;
        private String alignment;

        private String width;
        private List<Card> cards;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Card {
        private CardData data;
        private List<Suggestion> suggestions;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CardData {
        private Media media;
        private String title;
        private String description;
        private String orientation;
        private String height;
        private String alignment;
        private Map<String, String> parameters;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Media {
        private String media_url;
        private String content_type;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Suggestion {
        private String type;
        private String text;
        private String postback_data;
        
        private String start_time;
        private String end_time;
        private String title;
        private String description;
        private String country_code;
        private String number;
        private String url;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Sms {
        private String sender;
        private String message;
        private String template_id;
    }
}
