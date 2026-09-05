package com.seongmin.redislab.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** 프로세스 안 pub/sub: 구독자(실험 서비스)와 SSE 클라이언트에게 같은 이벤트를 전달한다. */
@Component
public class LabEventBus {

	private final List<Consumer<LabEvent>> listeners = new CopyOnWriteArrayList<>();
	private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
	private final ArrayDeque<LabEvent> recent = new ArrayDeque<>();
	private final ObjectMapper json;

	public LabEventBus(ObjectMapper json) {
		this.json = json;
	}

	public void subscribe(Consumer<LabEvent> l) { listeners.add(l); }

	public void publish(LabEvent e) {
		synchronized (recent) {
			if (!"sample".equals(e.kind()) && !"topology".equals(e.kind())) { recent.addLast(e); if (recent.size() > 500) recent.removeFirst(); }
		}
		for (Consumer<LabEvent> l : listeners) {
			try { l.accept(e); } catch (RuntimeException ignored) {}
		}
		if (emitters.isEmpty()) return;
		String data;
		try { data = json.writeValueAsString(e); } catch (IOException ex) { return; }
		for (SseEmitter em : emitters) {
			try { em.send(SseEmitter.event().name(e.kind()).data(data)); } catch (Exception ex) { emitters.remove(em); }
		}
	}

	public List<LabEvent> recent() { synchronized (recent) { return List.copyOf(recent); } }

	public SseEmitter register() {
		SseEmitter em = new SseEmitter(0L);
		emitters.add(em);
		em.onCompletion(() -> emitters.remove(em));
		em.onTimeout(() -> emitters.remove(em));
		em.onError(t -> emitters.remove(em));
		return em;
	}

	public void heartbeat() {
		for (SseEmitter em : emitters) {
			try { em.send(SseEmitter.event().comment("ping")); } catch (Exception ex) { emitters.remove(em); }
		}
	}
}
