package com.github.blarc.ai.commits.intellij.plugin.settings.prompts

enum class DefaultPrompts(val prompt: Prompt) {

    // Generate UUIDs for game objects in Mine.py and call the function in start_game().
    BASIC(
        Prompt(
            "Basic",
            "Basic prompt that generates a decent commit message.",
            "스마트 커밋 메시지 생성 프레임워크를 기반으로 현재 git diff를 분석하고, 고품질 커밋 메시지를 생성해주세요.\n" +
                    "\n" +
                    "**작업**: git staged 변경사항을 분석하여 완벽한 내용-코드 일관성을 가진 한국어로 커밋 메시지를 작성합니다.\n" +
                    "\n" +
                    "**BEST PRACTICES 기준** (필수 준수):\n" +
                    "\n" +
                    "**1. 내용-코드 일관성 (CRITICAL)**:\n" +
                    "- 메시지의 클래스명/메서드명이 실제 git diff의 변경사항과 100% 일치해야 함\n" +
                    "- 추상적 표현 금지: \"코드 수정\", \"기능 개선\", \"시스템 변경\" \n" +
                    "- 구체적 코드 요소명 필수: \"UserService 클래스의 findUserById 메서드\"\n" +
                    "**완벽한 예시 1**:\n" +
                    "[feature] 메타 도시명 코드 listDataCodeCityForCountryCode 오류수정\n" +
                    "\n" +
                    "dataCodeOutPort.listDataCodeCityForCountryCode 호출시 codeType 전달이 누락되어 있어 수정\n" +
                    "[JIRA TICKET No]\n" +
                    "\n" +
                    "→ 내용-코드 일관성: ✅ 정확한 컴포넌트/함수명, ✅ 구체적 최적화 기법, ✅ 명확한 성능 효과\n" +
                    "\n" +
                    "**분석 요구사항**:\n" +
                    "1. git diff에서 정확한 클래스명, 메서드명, 함수명을 추출하세요\n" +
                    "2. 커밋 타입을 식별하세요 (feat/fix/perf/refactor/style/test/chore/docs)\n" +
                    "3. 구체적인 WHAT정보를 50자 내외로 생성하세요 (변경대상 + 변경행위)\n" +
                    "4. 명확한 WHY정보를 50자 내외로 결정하세요 (변경이유 + 목적)\n" +
                    "\n" +
                    "**출력 형식**:\n" +
                    "[이슈번호][타입] WHAT정보 \n" +
                    "- WHY정보\n" +
                    "\n" +
                    "**검증 사항**:\n" +
                    "- ✅ 실제 코드 요소명이 사용됨\n" +
                    "- ✅ 구체적이고 정확함\n" +
                    "- ✅ 변경 이유가 명확함\n" +
                    "\n" +
                    "실행: 'git diff --cached' 명령어를 실행하고 이 프레임워크를 따라 진행하고, 불필요한 모든 내용은 제외하고 반드시 출력 형식으로만 출력하세요.",
            true
        )
    ),
    DETAIL(
        Prompt(
            "Detail",
            "Detail prompt that generates a decent commit message.",
            "스마트 커밋 메시지 생성 프레임워크를 기반으로 현재 git diff를 분석하고, 고품질 커밋 메시지를 생성해주세요.\n" +
                    "\n" +
                    "**작업**: git staged 변경사항을 분석하여 완벽한 내용-코드 일관성을 가진 한국어로 커밋 메시지를 작성합니다.\n" +
                    "\n" +
                    "**BEST PRACTICES 기준** (필수 준수):\n" +
                    "\n" +
                    "**1. 내용-코드 일관성 (CRITICAL)**:\n" +
                    "- 메시지의 클래스명/메서드명이 실제 git diff의 변경사항과 100% 일치해야 함\n" +
                    "- 추상적 표현 금지: \"코드 수정\", \"기능 개선\", \"시스템 변경\" \n" +
                    "- 구체적 코드 요소명 필수: \"UserService 클래스의 findUserById 메서드\"\n" +
                    "**완벽한 예시 1**:\n" +
                    "[feature] 메타 도시명 코드 listDataCodeCityForCountryCode 오류수정\n" +
                    "\n" +
                    "dataCodeOutPort.listDataCodeCityForCountryCode 호출시 codeType 전달이 누락되어 있어 수정\n" +
                    "[JIRA TICKET No]\n" +
                    "\n" +
                    "→ 내용-코드 일관성: ✅ 정확한 컴포넌트/함수명, ✅ 구체적 최적화 기법, ✅ 명확한 성능 효과\n" +
                    "\n" +
                    "**분석 요구사항**:\n" +
                    "1. git diff에서 정확한 클래스명, 메서드명, 함수명을 추출하세요\n" +
                    "2. 커밋 타입을 식별하세요 (feat/fix/perf/refactor/style/test/chore/docs)\n" +
                    "3. 구체적인 WHAT정보를 50자 내외로 생성하세요 (변경대상 + 변경행위)\n" +
                    "4. 명확한 WHY정보를 50자 내외로 결정하세요 (변경이유 + 목적)\n" +
                    "\n" +
                    "**출력 형식**:\n" +
                    "[이슈번호][타입] WHAT정보 \n" +
                    "- WHY정보\n" +
                    "\n" +
                    "**검증 사항**:\n" +
                    "- ✅ 실제 코드 요소명이 사용됨\n" +
                    "- ✅ 구체적이고 정확함\n" +
                    "- ✅ 변경 이유가 명확함\n" +
                    "\n" +
                    "실행: 'git diff --cached' 명령어를 실행하고 이 프레임워크를 따라 결과를 분석하세요.",
            true
        )
    );

    companion object {
        fun toPromptsMap(): MutableMap<String, Prompt> {
            return entries.associateBy({ it.name.lowercase() }, { it.prompt }).toMutableMap()
        }
    }
}
