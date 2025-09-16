export default class ChatRenderer {

	chatContainer = null;
	inputArea = null;
	submitBtn = null;
	pauseBtn = null;


	chatScrollable = null;
	UIstate = true;

	curContainer = null;
	curQueryBubble = null;
	curAnswerBubble = null;
	curAnswerBubbleInner = null

	constructor(components) {
		this.chatContainer = components.chatContainer,
		this.inputArea = components.inputArea,
		this.submitBtn = components.submitBtn,
		this.pauseBtn = components.pauseBtn
	}

	/**
	 * UI 상태를 초기 상태로 리셋
	 */
	toggleUIState() {
		console.log("toggle");
		this.UIstate = !this.UIstate;
		if (this.UIstate) {
			this.inputArea.prop('readonly', false);
			this.submitBtn.show();
			this.pauseBtn.hide();
		} else {
			this.inputArea.prop('readonly', true);
			this.submitBtn.hide();
			this.pauseBtn.show();
		}
	};

	initQuestionRender(questionId, question) {
		this.resetPointer();
		this.drawQueryBubble(questionId, question);
		this.drawAnswerBubble();
	}

	resetPointer() {
		this.curContainer = null;
		this.curQueryBubble = null;
		this.curAnswerBubble = null;
		this.curAnswerBubbleInner = null;
	}

	drawQueryBubble(questionId, question) {
		const $container = $('<div>').data('questionId', questionId);
		this.curContainer = $container;

		this.curContainer.append($container);

		const $queryBubble = $('<div>').addClass('query_bubble');
		this.curQueryBubble = $queryBubble;
		
		const $queryInner = $('<div>').addClass('query_bubble_inner');
		const $p = $('<p>').text(question); // HTML이 아닌 텍스트로 안전하게 삽입

		$queryInner.append($p);
		$queryBubble.append($queryInner);
		

		this.curContainer.append(this.curQueryBubble);
		
		this.chatContainer.append(this.curContainer);

	};

	drawAnswerBubble() {
		const $answerBubble = $('<div>').addClass('answer_bubble');
		this.curAnswerBubble = $answerBubble;

		const $answerInner = $('<div>').addClass('answer_bubble_inner');
		this.curAnswerBubbleInner = $answerInner;
		
		$answerBubble.append($answerInner);
		
		this.curContainer.append(this.curAnswerBubble);
	}

	updateAnswer(text) {
		this.curAnswerBubbleInner.html(text);
	}

	stopAnswer() {
		this.curAnswerBubbleInner.append("<p>[답변이 중지되었습니다.]</p>");
	}

	showErrorMessage(message) {
		this.curAnswerBubbleInner.append(`<p>[에러가 발생했습니다.] (${message})</p>`);
	};



}