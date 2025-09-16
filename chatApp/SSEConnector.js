export default class SSEConnector {

	conn = null;
	buffer = "";

	constructor(urls, chunkProcessor, handleSSEComplete, handleSSEStop, handleSSEError) {
		this.urls = urls;
		this.handleSSEComplete = handleSSEComplete;
		this.handleSSEStop = handleSSEStop;
		this.handleSSEError = handleSSEError;
		this.chunkProcessor = chunkProcessor;

	}

	/**
	 * fetch를 사용한 SSE 스트림 시작
	 * @param {Object} payload - 전송할 데이터
	 * @param {string} qstnSn - 질문 시퀀스 번호
	 * @param {string} ansSn - 답변 시퀀스 번호
	 * @param {boolean} isFirstChat - 첫 번째 채팅 여부
	 */
	connect(payload) {
		this.close();

		fetch(this.urls.connect, {
			method: 'POST',
			headers: {
				'Content-Type': 'application/json',
				'Accept': 'text/event-stream',
				'Cache-Control': 'no-cache'
			},
			body: JSON.stringify(payload)
		})
			.then(response => {
				if (!response.ok) {
					throw new Error(`HTTP ${response.status}: ${response.statusText}`);
				} else {
					console.log('✅ SSE 스트림 연결 성공');
				}

				// ReadableStream을 사용한 청킹 처리
				const conn = response.body.getReader();
				const decoder = new TextDecoder("UTF-8");

				const processStream = () => {
					return conn.read().then(({ done, value }) => {
						if (done) {
							
							this.handleSSEComplete();
							this.close();
							return;
						}

						const chunk = decoder.decode(value, { stream: true });
						this.parseChunk(chunk, this.chunkProcessor);
						// 다음 청크 처리
						processStream();
					});
				};

				processStream();
			})
			.catch(error => {
				console.error('❌ SSE 스트림 오류:', error);
				this.handleSSEError(error);
				this.close();
			});
	};

	/**
	 * 최적화된 직접 청크 처리 (단순화 버전)
	 * @param {string} chunk - 수신된 청크 데이터
	 * @param {Object} streamData - 스트림 상태 데이터
	 */
	parseChunk(chunk, chunkProcessor) {

		try {
			// SSE 데이터 추출
			const dataList = this.extractSSEData(chunk);
			for (const data of dataList) {

				// 특수 신호 확인
				if (data === '[DONE]') {
					console.log('✅ 스트림 완료 신호 수신');

					// 남은 버퍼 처리
					if (this.buffer && this.buffer.trim()) {
						chunkProcessor(this.buffer);
					}
					return;
				}

				if (data.startsWith('[STOPPED]')) {
					console.log('✅ 스트림 중지 신호 수신');
					this.handleSSEStop();
					//this.close();
					return;
				}

				// 일반 텍스트 데이터 처리
				if (data.trim() !== '') {
					const decodedData = data.replace(/&lt;\/br&gt;/g, '\n');
					this.buffer += decodedData;
					chunkProcessor(this.buffer);
				}
			}
		} catch (error) {
			console.error('청크 처리 오류:', error);
			//this.handleSSEError(error);
			//this.close();
		}
	};


	/**
	 * 개선된 SSE 데이터 추출 함수
	 * 데이터 손실 없이 정확한 추출
	 */
	extractSSEData(chunk) {
		const dataList = [];

		try {
			// 청크를 줄 단위로 분리 (CRLF, LF 모두 처리)
			const lines = chunk.split(/\r?\n/);

			for (const line of lines) {
				const trimmedLine = line.trim();

				if (trimmedLine.startsWith('data:')) {
					// 'data:' 이후의 모든 내용을 데이터로 처리 (공백 포함)
					const data = line.substring(5); // 'data:' 제거, trim 하지 않음

					// 특수 제어 메시지들만 필터링
					const trimmedData = data.trim();
					if (trimmedData !== 'connected' &&
						trimmedData !== 'heartbeat' &&
						trimmedData !== '') {

						// 원본 데이터 그대로 사용 (공백, 개행 보존)
						dataList.push(data);
					}
				}
			}

			return dataList;

		} catch (error) {
			console.error('SSE 데이터 추출 오류:', error);
			//this.handleSSEError(error);
			//this.close();
			return [];
		}
	};


	/**
	 * 기존 연결들을 모두 종료
	 * 새로운 연결을 시작하기 전에 기존 EventSource 및 SSE 연결을 정리
	 */
	close() {
		if (this.conn) {
			this.conn.cancel();
		}
		this.conn = null;
		this.buffer = "";
	};


	stop(payload) {
		fetch(this.urls.stop, {
			method: 'POST',
			headers: {
				'Content-Type': 'application/json'
			},
			body: JSON.stringify(payload)
		})
			.then(response => response.json())
			.then(result => {
				console.log('[사용자 중지] 중지 요청 완료:', result);
			})
			.catch(error => {
				console.error('[사용자 중지] 중지 요청 실패:', error);
			});
	}



}