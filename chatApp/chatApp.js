import SSEConnector from './SSEConnector.js';
import ChatRenderer from './ChatRenderer.js';
import * as Utils from './Utils.js'

export default class ChatApp {

  constructor(urls, chatComponents, config) {
    this.config = config
	this.urls = urls
	this.components = chatComponents

    this.renderer = new ChatRenderer(chatComponents);
    this.conn = new SSEConnector(this.urls,
      this.chunkProcessor.bind(this),
      this.handleSSEComplete.bind(this),
      this.handleSSEStop.bind(this),
      this.handleSSEError.bind(this));
      
    this.componentBinding(chatComponents);
  }
  
  componentBinding(components) {
	//components.chatContainer
	components.inputArea.on("keydown", (e) => {
		if(e.key === "Enter" && !e.shiftKey) {
			e.preventDefault();
			
			this.askQuestion();
		}
	})
	components.submitBtn.on("click", () => {
		this.askQuestion();
	})
	
	components.pauseBtn.on("click", () => {
		this.stopAnswer();
	})
  }
  
  isValidQuestion(){
	const question = this.components.inputArea.val();
	if(question == null || question == ""){
		console.warn("질문을 입력하지 않았습니다.");
		return false;
	}
	this.components.inputArea.val("");
	return question;
  }
  
  askQuestion() {
	const question = this.isValidQuestion();
	if(question) {
		const questionId = this.config.questionId;
		
	    this.renderer.initQuestionRender(questionId, question);
	
	    const payload = {
	      question: question,
	      user_id: this.config.userId,
	      session_id: this.config.sessionId,
	      isFdbk: this.config.isFdbk,
	      qstnSn: this.config.questionId
	    };
	
		this.preProcessor();
	    this.conn.connect(payload);
	}
	
  }

  stopAnswer() {
    const payload = {
      qstnSn: this.config.questionId,
      reason: 'USER_STOP',
      isFdbk: this.config.isFdbk
    };

    this.conn.stop(payload);
  }


  preProcessor() {
    this.renderer.toggleUIState();
  }

  chunkProcessor(text) {
	if(text!= null && text!= ""){
		const parsedText = Utils.markdown2Html(text);
	    this.renderer.updateAnswer(parsedText);
    }
  };

  handleSSEComplete() {
	console.log('📡 SSE 스트림 완료');
	
    this.renderer.resetPointer();
    this.renderer.toggleUIState();
  };

  handleSSEStop() {
    this.renderer.stopAnswer();

    //this.renderer.resetPointer();
    //this.renderer.toggleUIState();
  };

  handleSSEError(error) {
    const errorMessage = `SSE 연결 중 오류가 발생했습니다`; // : ${error.message}
    console.error('❌ SSE 오류:', error);
    this.renderer.showErrorMessage(`❌ ${errorMessage}`);

    this.renderer.resetPointer();
    this.renderer.toggleUIState();
  }
}