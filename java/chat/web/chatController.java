package egovframework.project.chat.web;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.beust.jcommander.internal.Console;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
@RequestMapping(value="/chat")
public class chatController {
	
	// LLM API 설정값들 주입
    @Value("${llm.api.url:http://59.25.177.42:8001/query}")
    private String llmApiUrl;
    
    @Value("${llm.api.timeout.connect:30000}")
    private int connectTimeout;
    
    @Value("${llm.api.timeout.read:120000}")
    private int readTimeout;
    
    // SSE Emitter 관리 (sessionId_qstnSn -> SseEmitter)
    private final Map<String, SseEmitter> emitterMap = new ConcurrentHashMap<>();

    // 진행중인 LLM 요청 관리 (questionSn -> 응답 상태)
    private final Map<String, LLMResponseStatus> responseStatusMap = new ConcurrentHashMap<>();
	
	
	@GetMapping("/")
	public String chatFragment() {
		return "fragment/chatFragment";
	}
	
	
	@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    @ResponseBody
    public SseEmitter startStream(@RequestBody Map<String, Object> request) {
        String question = (String) request.get("question");
        String userId = (String) request.get("user_id"); 
        String sessionId = (String) request.get("session_id");
        String qstnSn = (String) request.get("qstnSn");
        boolean isFdbk = (boolean) request.get("isFdbk");
        
        log.info("=== SSE 스트림 시작 ===");
        log.info("🔗 LLM API: {}", llmApiUrl);
        log.info("질문: {}", question.length() > 100 ? question.substring(0, 100) + "..." : question);
        log.info("qstnSn: {}", qstnSn);
        log.info("==================");
        
        
        // SSE Emitter 생성 (30분 타임아웃, 더 넉넉하게)
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        String emitterKey = sessionId + "_" + qstnSn;
        
        // 기존 emitter가 있다면 정리
        SseEmitter existingEmitter = emitterMap.get(emitterKey);
        if (existingEmitter != null) {
            try {
                existingEmitter.complete();
            } catch (Exception e) {
                log.warn("기존 emitter 정리 중 오류: {}", e.getMessage());
            }
        }
        
        emitterMap.put(emitterKey, emitter);

        // Emitter 이벤트 핸들러 설정
        emitter.onCompletion(() -> {
            log.info("✅ SSE 연결 완료: {}", emitterKey);
            emitterMap.remove(emitterKey);
            cleanupResponseStatus(qstnSn);
        });
        
        emitter.onTimeout(() -> {
            log.warn("⏰ SSE 타임아웃: {}", emitterKey);
            emitterMap.remove(emitterKey);
            cleanupResponseStatus(qstnSn);
            emitter.complete();
        });
        
        emitter.onError((ex) -> {
            log.warn("⚠️ SSE 클라이언트 연결 끊김: {} - {}", emitterKey, ex.getMessage());
            // emitter만 제거하고 LLM 응답 처리는 계속 진행
            emitterMap.remove(emitterKey);
            
            // 응답 상태에 클라이언트 연결 끊김 표시 (LLM 처리는 계속)
            LLMResponseStatus status = responseStatusMap.get(qstnSn);
            if (status != null) {
                status.setClientDisconnected(true);
            }
        });
        
        // 연결 확인 신호 전송 (개선된 방식)
        try {
            sendSSEEvent(emitter, "connect", "connected");
            log.info("🔗 SSE 연결 신호 전송 완료");
        } catch (IOException e) {
            log.error("❌ SSE 연결 신호 전송 실패", e);
            emitter.completeWithError(e);
            return emitter;
        }
        
        // 비동기로 LLM API 호출 시작
        startLLMRequest(question, userId, sessionId, qstnSn, emitterKey, isFdbk);
        
        return emitter;
    }

    /**
     * SSE 스트림 중지 엔드포인트
     */
    @PostMapping("/stop")
    @ResponseBody
    public Map<String, Object> stopStream(@RequestBody Map<String, Object> request) {
        String qstnSn = (String) request.get("qstnSn");
        String reason = (String) request.getOrDefault("reason", "USER_REQUEST");
        boolean isFdbk = (boolean) request.get("isFdbk");
        
        log.info("⏹️ SSE 스트림 중지 요청 - qstnSn: {}, reason: {}", qstnSn, reason);
        
        // 해당 응답을 중지 처리
        stopLLMResponse(qstnSn, reason, isFdbk);
        
        Map<String, Object> response = new HashMap<>();
        response.put("result", "success");
        response.put("message", "스트림이 중지되었습니다.");
        response.put("qstnSn", qstnSn);
        
        return response;
    }
    
    /**
     * LLM API 상태 확인 엔드포인트
     */
    @GetMapping("/llm-status")
    @ResponseBody
    public Map<String, Object> checkLLMStatus() {
        Map<String, Object> status = new HashMap<>();
        
        try {
            // 간단한 연결 테스트
            URL url = new URL(llmApiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            int responseCode = conn.getResponseCode();
            
            status.put("status", responseCode < 500 ? "UP" : "DOWN");
            status.put("llmApiUrl", llmApiUrl);
            status.put("responseCode", responseCode);
            status.put("connectTimeout", connectTimeout);
            status.put("readTimeout", readTimeout);
            
            log.info("✅ LLM API 상태 확인: {} ({})", status.get("status"), responseCode);
            
        } catch (Exception e) {
            status.put("status", "DOWN");
            status.put("error", e.getMessage());
            status.put("llmApiUrl", llmApiUrl);
            
            log.error("❌ LLM API 연결 실패: {}", e.getMessage());
        }
        
        return status;
    }
    
    private void startLLMRequest(String question, String userId, String sessionId, String qstnSn, 
            String emitterKey, boolean isFdbk) {
		// 응답 상태 초기화
		LLMResponseStatus status = new LLMResponseStatus();
		status.setQuestionSn(qstnSn);
		status.setSessionId(sessionId);
		status.setInProgress(true);
		status.setResponse(new StringBuilder());
		status.setCleanedResponse("");
		status.setEmitterKey(emitterKey);
		
		responseStatusMap.put(qstnSn, status);
		
		// 비동기로 LLM API 호출
		CompletableFuture.runAsync(() -> {
			try {
				callLLMAPI(question, userId, sessionId, qstnSn, emitterKey, isFdbk);
			} catch (Exception e) {
				handleLLMError(qstnSn, "❌ LLM API 연결 오류", "API001", emitterKey, isFdbk);
				log.error("LLM API 호출 실패", e);
			}
		});
	}
    


    private void callLLMAPI(String question, String userId, String sessionId, String qstnSn, String emitterKey, boolean isFdbk) throws Exception {
        
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> apiRequest = new HashMap<>();
        apiRequest.put("question", question);
        apiRequest.put("user_id", userId);
        apiRequest.put("session_id", sessionId);
        

        String jsonPayload = objectMapper.writeValueAsString(apiRequest);

        log.info("🔗 LLM API 호출: {}", llmApiUrl);
        
        
        URL url = new URL(llmApiUrl);
        	
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Accept", "text/plain; charset=UTF-8");
        conn.setDoOutput(true);
        

        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        log.info("⏱️ 기본 타임아웃 설정: {}ms", readTimeout);

        try (OutputStreamWriter osw = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
            osw.write(jsonPayload);
            osw.flush();
            log.info("📤 LLM API 요청 전송 완료");
        } catch (Exception e) {
            handleLLMError(qstnSn, "❌ LLM API 연결 오류", "API001", emitterKey, isFdbk);
            log.error("LLM API 요청 전송 실패", e);
            return;
        }

        // 응답 처리
        if (conn.getResponseCode() == 200) {
            log.info("✅ LLM API 응답 수신 시작");
            processLLMResponse(conn, qstnSn, emitterKey, isFdbk);
        } else {
            handleLLMError(qstnSn, "❌ LLM API 오류: HTTP " + conn.getResponseCode(), 
                          String.valueOf(conn.getResponseCode()), emitterKey, isFdbk);
        }
    }

    /**
     * LLM 응답 처리 (마지막 청크 중복 방지)
     */
    private void processLLMResponse(HttpURLConnection conn, String qstnSn, String emitterKey, boolean isFdbk) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            
            StringBuilder buffer = new StringBuilder();
            StringBuilder wordBuffer = new StringBuilder();
            long lastUpdate = System.currentTimeMillis();
            final long updateInterval = 100;
            boolean isCompleted = false; // ✅ 추가: 완료 플래그

            char[] charBuffer = new char[1];
            while (reader.read(charBuffer, 0, 1) != -1) {
                LLMResponseStatus status = responseStatusMap.get(qstnSn);
                if (status == null || !status.isInProgress() || status.isStopped()) {
                    log.info("⏹️ 응답 중지 신호 감지, 스트림 종료: {}", qstnSn);
                    isCompleted = true; // ✅ 완료 표시
                    break;
                }
                
                char ch = charBuffer[0];
                if (ch == '~') ch = '∼';

                wordBuffer.append(ch);

                if (Character.isWhitespace(ch) || ".!?,;\n".indexOf(ch) >= 0) {
                    buffer.append(wordBuffer);
                    wordBuffer.setLength(0);
                    
                    if (buffer.length() > 0) {
                        String chunk = buffer.toString();
                        handleLLMChunk(qstnSn, chunk, emitterKey);
                        buffer.setLength(0);
                        lastUpdate = System.currentTimeMillis();
                    }
                } else {
                    long now = System.currentTimeMillis();
                    if (now - lastUpdate > updateInterval && wordBuffer.length() > 5) {
                        buffer.append(wordBuffer);
                        wordBuffer.setLength(0);
                        
                        String chunk = buffer.toString();
                        handleLLMChunk(qstnSn, chunk, emitterKey);
                        buffer.setLength(0);
                        lastUpdate = now;
                    }
                }
            }

            // ✅ 수정: 정상 완료된 경우에만 남은 버퍼 처리
            if (!isCompleted) {
                if (wordBuffer.length() > 0) {
                    buffer.append(wordBuffer);
                }
                if (buffer.length() > 0) {
                    handleLLMChunk(qstnSn, buffer.toString(), emitterKey);
                }
            }

            handleLLMComplete(qstnSn, emitterKey, isFdbk);
            
        } catch (IOException e) {
            log.error("❌ LLM 응답 처리 중 오류", e);
            handleLLMError(qstnSn, "❌ 응답 처리 오류", "READ_ERROR", emitterKey, isFdbk);
        }
    }

    /**
     * LLM 청크 처리 (개선된 버전)
     */
    private void handleLLMChunk(String qstnSn, String chunk, String emitterKey) {
        LLMResponseStatus status = responseStatusMap.get(qstnSn);
        if (status == null || !status.isInProgress() || status.isStopped()) {
            return;
        }

        // 응답 누적 (클라이언트 연결 상태와 무관하게 계속)
        status.getResponse().append(chunk);
        String chunkToSend = chunk;
        String lastCleanedResponse = status.getCleanedResponse();
        if (lastCleanedResponse == null) lastCleanedResponse = "";
        status.setCleanedResponse(lastCleanedResponse + chunk);

        // 클라이언트가 연결되어 있을 때만 SSE 전송 시도
        if (!status.isClientDisconnected()) {
            SseEmitter emitter = emitterMap.get(emitterKey);
            if (emitter != null && !chunkToSend.isEmpty()) {
                try {
                	log.info(chunkToSend);
                    sendSSEData(emitter, chunkToSend);
                } catch (Exception e) {
                    log.warn("⚠️ 클라이언트 연결 끊김 감지, SSE 전송 중단하지만 LLM 응답은 계속 처리");
                    status.setClientDisconnected(true);
                    emitterMap.remove(emitterKey);
                }
            }
        }
    }

    
    /**
     * LLM 완료 처리 (개선된 버전)
     */
    private void handleLLMComplete(String qstnSn, String emitterKey, boolean isFdbk) {
        LLMResponseStatus status = responseStatusMap.get(qstnSn);
        if (status == null) return;

        status.setInProgress(false);
        status.setCompleted(true);
        
        String fullResponse = status.getResponse().toString();
        
        log.info("✅ LLM 응답 완료 - 길이: {} 문자, 클라이언트 연결: {}", 
                 fullResponse.length(), !status.isClientDisconnected());
        
        // 클라이언트가 연결되어 있을 때만 SSE 완료 신호 전송
        if (!status.isClientDisconnected()) {
            SseEmitter emitter = emitterMap.get(emitterKey);
            if (emitter != null) {
                try {
                    sendSSEEvent(emitter, "complete", "[DONE]");
                    emitter.complete();
                    log.info("✅ SSE 완료 신호 전송 완료");
                } catch (IOException e) {
                    log.warn("⚠️ SSE 완료 신호 전송 실패 (클라이언트 이미 연결 끊김)");
                }
            }
        }
        
        // 정리
        responseStatusMap.remove(qstnSn);
        emitterMap.remove(emitterKey);
    }

    /**
     * LLM 오류 처리 (개선된 버전)
     */
    private void handleLLMError(String qstnSn, String errorMessage, String errorCode, String emitterKey, boolean isFdbk) {
        LLMResponseStatus status = responseStatusMap.get(qstnSn);
        if (status == null) return;

        status.setInProgress(false);
        status.setError(true);
        status.setErrorMessage(errorMessage);
        
        log.error("❌ LLM 오류 - qstnSn: {}, 메시지: {}", qstnSn, errorMessage);
        
        
        // SSE 에러 전송
        SseEmitter emitter = emitterMap.get(emitterKey);
        if (emitter != null) {
            try {
                sendSSEEvent(emitter, "error", errorMessage);
                emitter.complete();
            } catch (IOException e) {
                log.error("❌ SSE 에러 전송 실패", e);
            }
        }
        
        // 정리
        responseStatusMap.remove(qstnSn);
        emitterMap.remove(emitterKey);
    }

    /**
     * LLM 응답 중지 처리
     */
    private void stopLLMResponse(String qstnSn, String reason, boolean isFdbk) {
        LLMResponseStatus status = responseStatusMap.get(qstnSn);
        
        if (status != null && status.isInProgress()) {
            log.info("⏹️ LLM 응답 중지 - qstnSn: {}, 이유: {}", qstnSn, reason);
            
            status.setInProgress(false);
            status.setStopped(true);
            status.setStopReason(reason);
            
            // SSE 중지 신호 전송
            String emitterKey = status.getEmitterKey();
            SseEmitter emitter = emitterMap.get(emitterKey);
            if (emitter != null) {
                try {
                    sendSSEEvent(emitter, "stopped", "[STOPPED]");
                    emitter.complete();
                } catch (IOException e) {
                    log.error("❌ SSE 중지 신호 전송 실패", e);
                }
            }
            
        }
    }

    /**
     * SSE 이벤트 전송 (개선된 버전)
     */
    private void sendSSEEvent(SseEmitter emitter, String eventName, String data) throws IOException {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data)
                    .reconnectTime(3000));
        } catch (IOException e) {
            log.warn("⚠️ SSE 전송 실패 (클라이언트 연결 끊김): {}", e.getMessage());
            // IOException은 무시하고 계속 진행
        }
    }

    /**
     * SSE 데이터 전송 (개선된 버전)
     */
    private void sendSSEData(SseEmitter emitter, String data) throws IOException {
        try {
            String safeData = data
                .replace("\\", "\\\\")
                .replace("\n", "&lt;/br&gt;")
                .replace("\r", "");

            emitter.send(SseEmitter.event()
                    .name("data")
                    .data(safeData)
                    .reconnectTime(3000));
        } catch (IOException e) {
            log.warn("⚠️ SSE 데이터 전송 실패 (클라이언트 연결 끊김): {}", e.getMessage());
            // IOException은 무시하고 계속 진행
        }
    }

    /**
     * 응답 상태 정리
     */
    private void cleanupResponseStatus(String qstnSn) {
        LLMResponseStatus status = responseStatusMap.get(qstnSn);
        if (status != null) {
            status.setInProgress(false);
            responseStatusMap.remove(qstnSn);
        }
    }

    
    
    @Getter
    @Setter
    private static class LLMResponseStatus {
        private String questionSn;
        private String sessionId;
        private String emitterKey;
        private boolean inProgress;
        private boolean completed;
        private boolean error;
        private String errorMessage;
        private StringBuilder response = new StringBuilder();
        private String cleanedResponse;
        private List<String> extractedQuestions;
        private String fileInfo;
        private List<String> fileInfoList;
        private boolean stopped = false;
        private String stopReason;
        private boolean clientDisconnected = false; // 추가
    }
    
    
    
    
    
    
}
