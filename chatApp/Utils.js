
/**
 * 마크다운 컨텐츠 변환
 */
export function markdown2Html(markdownText) {
  if (markdownText && markdownText.length > 0) {
    const parsedHtml = marked.parse(markdownText);

    let finalHtml = parsedHtml;

    return finalHtml;
  } else {
    return markdownText;
  }
}

/**
 * 타자기 효과 구현 함수
 * 텍스트를 한 글자씩 순차적으로 타이핑하는 애니메이션 효과
 * @param {jQuery} element - 타이핑할 대상 요소
 * @param {string} text - 타이핑할 전체 텍스트
 * @param {number} speed - 타이핑 속도 (기본값: 50ms)
 */
export function typeWriterEffect(element, text, speed = 50) {
  // 이미 타이핑 중이거나 요소가 없으면 중단
  if (!element || !element.length) {
    console.warn('⚠️ 타이핑 대상 요소가 없음');
    return;
  }


  return new Promise((resolve) => {
    element.text('');
    let index = 0;

    const intervalId = setInterval(() => {
      if (index < text.length) {
        element.text(element.text() + text.charAt(index));
        index++;
      } else {
        clearInterval(intervalId);  // 반복 종료
        resolve();                 // Promise 완료
      }
    }, speed);
  });

};

