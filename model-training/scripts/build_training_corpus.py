#!/usr/bin/env python3
"""Build deterministic synthetic Grayin training and held-out evaluation data."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from prompt_contract import (
    EVIDENCE_PACK_SCHEMA_VERSION,
    PROMPT_CONTRACT_VERSION,
    build_grounded_answer,
    render_evidence_pack_prompt,
)


LANGUAGES = ("en", "ko", "ja")
TRAIN_SOURCE = "synthetic_train_v1"
EVAL_SOURCE = "synthetic_eval_v1"


def text(en: str, ko: str, ja: str) -> dict[str, str]:
    return {"en": en, "ko": ko, "ja": ja}


def terms(en: str, ko: str, ja: str) -> dict[str, list[str]]:
    return {"en": [en], "ko": [ko], "ja": [ja]}


SCENARIOS: list[dict[str, Any]] = [
    {
        "family": "yesterday_route",
        "evidence_specs": [
            {
                "event_kind": "PLACE_VISIT",
                "confidence": "HIGH",
                "capabilities": ["HAS_TIME", "HAS_LOCATION"],
            },
        ],
        "missing_specs": [{"capability": "HAS_MEDIA", "availability": "NOT_INDEXED", "connector_id": "photos"}],
        "confidence": "HIGH",
        "train": {
            "query": text("Where did I go yesterday evening?", "어제 저녁에 어디에 갔어?", "昨日の夕方はどこへ行った？"),
            "summaries": [text(
                "A rounded location sample indicates a City Hall visit from 18:10 to 19:00.",
                "반올림된 위치 표본에 따르면 18시 10분부터 19시까지 시청 근처에 머물렀다.",
                "丸めた位置サンプルでは18時10分から19時まで市庁付近に滞在していた。",
            )],
            "occurred_at": ["2026-06-29T18:10:00Z"],
            "missing": [text(
                "Photos were not indexed for that period.",
                "해당 시간대의 사진은 인덱싱되지 않았다.",
                "その時間帯の写真はインデックスされていない。",
            )],
            "answer": text(
                "The indexed location evidence shows a visit near City Hall from 18:10 to 19:00.",
                "인덱싱된 위치 근거에는 18시 10분부터 19시까지 시청 근처에 머문 기록이 있다.",
                "インデックス済みの位置根拠では18時10分から19時まで市庁付近に滞在していた。",
            ),
            "required": terms("City Hall", "시청", "市庁"),
        },
        "eval": {
            "query": text("Where did I go last Friday night?", "지난 금요일 밤에 어디에 갔어?", "先週の金曜の夜はどこへ行った？"),
            "summaries": [text(
                "A rounded location sample indicates a Hongdae visit from 20:20 to 21:05.",
                "반올림된 위치 표본에 따르면 20시 20분부터 21시 5분까지 홍대 근처에 머물렀다.",
                "丸めた位置サンプルでは20時20分から21時05分まで弘大付近に滞在していた。",
            )],
            "occurred_at": ["2026-06-26T20:20:00Z"],
            "missing": [text(
                "Photos were not indexed for Friday night.",
                "금요일 밤의 사진은 인덱싱되지 않았다.",
                "金曜夜の写真はインデックスされていない。",
            )],
            "answer": text(
                "The indexed location evidence shows a visit near Hongdae from 20:20 to 21:05.",
                "인덱싱된 위치 근거에는 20시 20분부터 21시 5분까지 홍대 근처에 머문 기록이 있다.",
                "インデックス済みの位置根拠では20時20分から21時05分まで弘大付近に滞在していた。",
            ),
            "required": terms("Hongdae", "홍대", "弘大"),
        },
    },
    {
        "family": "daily_activity",
        "evidence_specs": [
            {
                "event_kind": "DAILY_SUMMARY",
                "confidence": "MEDIUM",
                "capabilities": ["HAS_TIME", "HAS_CALENDAR", "HAS_APP_USAGE"],
            },
        ],
        "missing_specs": [{"capability": "HAS_LOCATION", "availability": "DISABLED", "connector_id": "location"}],
        "confidence": "MEDIUM",
        "train": {
            "query": text("What did I do on Tuesday?", "화요일에 무엇을 했어?", "火曜日は何をした？"),
            "summaries": [text(
                "Tuesday's derived summary contains a planning meeting and two hours of study-app activity.",
                "화요일 파생 요약에는 기획 회의와 학습 앱 사용 2시간이 기록되어 있다.",
                "火曜日の派生要約には企画会議と学習アプリの利用2時間が記録されている。",
            )],
            "occurred_at": ["2026-06-23T00:00:00Z"],
            "missing": [text("Location indexing was disabled.", "위치 인덱싱이 비활성화되어 있었다.", "位置情報のインデックスは無効だった。")],
            "answer": text(
                "Tuesday's indexed summary records a planning meeting and two hours of study-app activity.",
                "화요일 인덱싱 요약에는 기획 회의와 학습 앱 사용 2시간이 기록되어 있다.",
                "火曜日のインデックス要約には企画会議と学習アプリの利用2時間が記録されている。",
            ),
            "required": terms("planning meeting", "기획 회의", "企画会議"),
        },
        "eval": {
            "query": text("What did I do on Wednesday?", "수요일에 무엇을 했어?", "水曜日は何をした？"),
            "summaries": [text(
                "Wednesday's derived summary contains a code review and ninety minutes of writing-app activity.",
                "수요일 파생 요약에는 코드 리뷰와 글쓰기 앱 사용 90분이 기록되어 있다.",
                "水曜日の派生要約にはコードレビューと文章作成アプリの利用90分が記録されている。",
            )],
            "occurred_at": ["2026-06-24T00:00:00Z"],
            "missing": [text("Location indexing was disabled.", "위치 인덱싱이 비활성화되어 있었다.", "位置情報のインデックスは無効だった。")],
            "answer": text(
                "Wednesday's indexed summary records a code review and ninety minutes of writing-app activity.",
                "수요일 인덱싱 요약에는 코드 리뷰와 글쓰기 앱 사용 90분이 기록되어 있다.",
                "水曜日のインデックス要約にはコードレビューと文章作成アプリの利用90分が記録されている。",
            ),
            "required": terms("code review", "코드 리뷰", "コードレビュー"),
        },
    },
    {
        "family": "drinking_context",
        "evidence_specs": [
            {
                "event_kind": "PLACE_VISIT",
                "confidence": "MEDIUM",
                "capabilities": ["HAS_TIME", "HAS_LOCATION"],
            },
            {
                "event_kind": "PAYMENT",
                "confidence": "HIGH",
                "capabilities": ["HAS_TIME", "HAS_PAYMENT"],
            },
        ],
        "missing_specs": [{"capability": "HAS_MEDIA", "availability": "NOT_INDEXED", "connector_id": "photos"}],
        "confidence": "MEDIUM",
        "forbidden": terms("definitely drank", "확실히 마셨다", "確実に飲酒した"),
        "train": {
            "query": text("Was I drinking last Saturday?", "지난 토요일에 술을 마셨나?", "先週の土曜日に飲酒していた？"),
            "summaries": [
                text("A night location cluster was derived near a pub district.", "밤 시간대에 주점가 근처 위치 군집이 파생되었다.", "夜間に居酒屋街付近の位置クラスターが派生した。"),
                text("A notification-derived payment was recorded at River Pub.", "알림에서 River Pub 결제 이벤트가 파생되었다.", "通知からRiver Pubでの支払いイベントが派生した。"),
            ],
            "occurred_at": ["2026-06-20T21:10:00Z", "2026-06-20T21:35:00Z"],
            "missing": [text("No photo labels were indexed.", "사진 라벨은 인덱싱되지 않았다.", "写真ラベルはインデックスされていない。")],
            "answer": text(
                "The Blue Bar payment and nearby location cluster suggest a drinking context, but do not confirm consumption.",
                "Blue Bar 결제와 인근 위치 군집은 음주 정황을 시사하지만 실제 음주 여부를 확인하지는 못한다.",
                "Blue Barでの支払いと付近の位置クラスターは飲酒の可能性を示すが、実際の飲酒は確認できない。",
            ),
            "required": terms("drinking context", "음주 정황", "飲酒の可能性"),
        },
        "eval": {
            "query": text("Was I drinking last Thursday?", "지난 목요일에 술을 마셨나?", "先週の木曜日に飲酒していた？"),
            "summaries": [
                text("A night location cluster was derived near a bar street.", "밤 시간대에 바 거리 근처 위치 군집이 파생되었다.", "夜間にバー街付近の位置クラスターが派生した。"),
                text("A notification-derived payment was recorded at Blue Bar.", "알림에서 Blue Bar 결제 이벤트가 파생되었다.", "通知からBlue Barでの支払いイベントが派生した。"),
            ],
            "occurred_at": ["2026-06-25T20:40:00Z", "2026-06-25T21:02:00Z"],
            "missing": [text("No photo labels were indexed.", "사진 라벨은 인덱싱되지 않았다.", "写真ラベルはインデックスされていない。")],
            "answer": text(
                "The location and payment evidence suggests a drinking context, but it cannot confirm consumption.",
                "위치와 결제 근거는 음주 정황을 시사하지만 실제 음주 여부는 확인할 수 없다.",
                "位置と支払いの根拠は飲酒の可能性を示すが、実際の飲酒は確認できない。",
            ),
            "required": terms("drinking context", "음주 정황", "飲酒の可能性"),
        },
    },
    {
        "family": "family_call_missing",
        "evidence_specs": [],
        "missing_specs": [
            {"capability": "HAS_PERSON", "availability": "UNSUPPORTED", "connector_id": "calls"},
            {"capability": "HAS_TEXT", "availability": "NOT_INDEXED", "connector_id": "local_files"},
        ],
        "confidence": "UNKNOWN",
        "train": {
            "query": text("Did I call my family this week?", "이번 주에 가족에게 전화했어?", "今週、家族に電話した？"),
            "summaries": [],
            "occurred_at": [],
            "missing": [
                text("Direct call evidence is unsupported.", "직접 통화 근거는 지원되지 않는다.", "直接の通話根拠はサポートされていない。"),
                text("No indexed notes mention a family call.", "인덱싱된 메모에 가족 통화 언급이 없다.", "インデックス済みノートに家族との通話記録がない。"),
            ],
            "answer": text(
                "I cannot determine whether a family call happened from the indexed evidence.",
                "인덱싱된 근거만으로 가족과 통화했는지 판단할 수 없다.",
                "インデックス済みの根拠だけでは家族に電話したか判断できない。",
            ),
            "required": terms("cannot determine", "판단할 수 없다", "判断できない"),
        },
        "eval": {
            "query": text("Did I call my parents last week?", "지난주에 부모님께 전화했어?", "先週、両親に電話した？"),
            "summaries": [],
            "occurred_at": [],
            "missing": [
                text("Direct call evidence is unsupported.", "직접 통화 근거는 지원되지 않는다.", "直接の通話根拠はサポートされていない。"),
                text("No indexed notes mention a call to parents.", "인덱싱된 메모에 부모님 통화 언급이 없다.", "インデックス済みノートに両親との通話記録がない。"),
            ],
            "answer": text(
                "I cannot determine whether a call to your parents happened from the indexed evidence.",
                "인덱싱된 근거만으로 부모님과 통화했는지 판단할 수 없다.",
                "インデックス済みの根拠だけでは両親に電話したか判断できない。",
            ),
            "required": terms("cannot determine", "판단할 수 없다", "判断できない"),
        },
    },
    {
        "family": "meetings",
        "evidence_specs": [{"event_kind": "CALENDAR_EVENT", "confidence": "HIGH", "capabilities": ["HAS_TIME", "HAS_CALENDAR"]}],
        "missing_specs": [{"capability": "HAS_TEXT", "availability": "NOT_INDEXED", "connector_id": "local_files"}],
        "confidence": "HIGH",
        "train": {
            "query": text("What meetings did I have yesterday?", "어제 어떤 회의가 있었어?", "昨日はどんな会議があった？"),
            "summaries": [text("Calendar event: Design Review, 10:00 to 10:45.", "캘린더 이벤트: 디자인 리뷰, 10시부터 10시 45분까지.", "カレンダー予定: デザインレビュー、10時から10時45分。")],
            "occurred_at": ["2026-06-29T10:00:00Z"],
            "missing": [text("Meeting notes were not indexed.", "회의 메모는 인덱싱되지 않았다.", "会議ノートはインデックスされていない。")],
            "answer": text("The calendar shows Design Review from 10:00 to 10:45.", "캘린더에는 10시부터 10시 45분까지 디자인 리뷰가 있다.", "カレンダーには10時から10時45分までデザインレビューがある。"),
            "required": terms("Design Review", "디자인 리뷰", "デザインレビュー"),
        },
        "eval": {
            "query": text("What meetings did I have on Monday?", "월요일에 어떤 회의가 있었어?", "月曜日はどんな会議があった？"),
            "summaries": [text("Calendar event: Release Check, 14:00 to 14:30.", "캘린더 이벤트: 릴리스 점검, 14시부터 14시 30분까지.", "カレンダー予定: リリース確認、14時から14時30分。")],
            "occurred_at": ["2026-06-22T14:00:00Z"],
            "missing": [text("Meeting notes were not indexed.", "회의 메모는 인덱싱되지 않았다.", "会議ノートはインデックスされていない。")],
            "answer": text("The calendar shows Release Check from 14:00 to 14:30.", "캘린더에는 14시부터 14시 30분까지 릴리스 점검이 있다.", "カレンダーには14時から14時30分までリリース確認がある。"),
            "required": terms("Release Check", "릴리스 점검", "リリース確認"),
        },
    },
    {
        "family": "future_busyness",
        "evidence_specs": [{"event_kind": "CALENDAR_EVENT", "confidence": "MEDIUM", "capabilities": ["HAS_TIME", "HAS_CALENDAR"]}],
        "missing_specs": [{"capability": "HAS_TEXT", "availability": "NOT_INDEXED", "connector_id": "local_files"}],
        "confidence": "MEDIUM",
        "train": {
            "query": text("Am I busy next week?", "다음 주에 바빠?", "来週は忙しい？"),
            "summaries": [text("The indexed calendar has nine events across four weekdays next week.", "인덱싱된 캘린더에는 다음 주 4일 동안 이벤트 9개가 있다.", "インデックス済みカレンダーには来週4日間で9件の予定がある。")],
            "occurred_at": ["2026-07-06T00:00:00Z"],
            "missing": [text("Future commitments in notes were not indexed.", "메모의 향후 일정은 인덱싱되지 않았다.", "ノート内の今後の予定はインデックスされていない。")],
            "answer": text("The indexed calendar suggests a busy week with nine events across four weekdays.", "인덱싱된 캘린더상 평일 4일에 이벤트 9개가 있어 바쁜 주로 보인다.", "インデックス済みカレンダーでは平日4日間に9件の予定があり、忙しい週と考えられる。"),
            "required": terms("nine events", "이벤트 9개", "9件の予定"),
        },
        "eval": {
            "query": text("How busy is the week after next?", "다다음 주는 얼마나 바빠?", "再来週はどのくらい忙しい？"),
            "summaries": [text("The indexed calendar has six events across three weekdays in the week after next.", "인덱싱된 캘린더에는 다다음 주 3일 동안 이벤트 6개가 있다.", "インデックス済みカレンダーには再来週3日間で6件の予定がある。")],
            "occurred_at": ["2026-07-13T00:00:00Z"],
            "missing": [text("Future commitments in notes were not indexed.", "메모의 향후 일정은 인덱싱되지 않았다.", "ノート内の今後の予定はインデックスされていない。")],
            "answer": text("The indexed calendar shows six events across three weekdays in the week after next.", "인덱싱된 캘린더에는 다다음 주 평일 3일에 이벤트 6개가 있다.", "インデックス済みカレンダーでは再来週の平日3日間に6件の予定がある。"),
            "required": terms("six events", "이벤트 6개", "6件の予定"),
        },
    },
    {
        "family": "food_photos",
        "evidence_specs": [{"event_kind": "PHOTO_CLUSTER", "confidence": "MEDIUM", "capabilities": ["HAS_TIME", "HAS_MEDIA", "HAS_VISUAL_LABEL"]}],
        "missing_specs": [{"capability": "HAS_LOCATION", "availability": "DENIED", "connector_id": "location"}],
        "confidence": "MEDIUM",
        "train": {
            "query": text("Find food photos from last month.", "지난달 음식 사진을 찾아줘.", "先月の食べ物の写真を探して。"),
            "summaries": [text("A photo cluster from May contains three derived ramen labels.", "5월 사진 군집에 라멘 파생 라벨 3개가 있다.", "5月の写真クラスターにラーメンの派生ラベルが3件ある。")],
            "occurred_at": ["2026-05-18T11:30:00Z"],
            "missing": [text("Location permission was denied, so place context is unavailable.", "위치 권한이 거부되어 장소 맥락을 사용할 수 없다.", "位置情報の権限が拒否され、場所の文脈は利用できない。")],
            "answer": text("The derived photo index contains a May cluster with three ramen labels.", "파생 사진 인덱스에는 5월 라멘 라벨 사진 군집 3개가 있다.", "派生写真インデックスには5月のラーメンラベルのクラスターが3件ある。"),
            "required": terms("ramen", "라멘", "ラーメン"),
        },
        "eval": {
            "query": text("Find dessert photos from April.", "4월 디저트 사진을 찾아줘.", "4月のデザート写真を探して。"),
            "summaries": [text("A photo cluster from April contains two derived cake labels.", "4월 사진 군집에 케이크 파생 라벨 2개가 있다.", "4月の写真クラスターにケーキの派生ラベルが2件ある。")],
            "occurred_at": ["2026-04-12T15:20:00Z"],
            "missing": [text("Location permission was denied, so place context is unavailable.", "위치 권한이 거부되어 장소 맥락을 사용할 수 없다.", "位置情報の権限が拒否され、場所の文脈は利用できない。")],
            "answer": text("The derived photo index contains an April cluster with two cake labels.", "파생 사진 인덱스에는 4월 케이크 라벨 사진 군집 2개가 있다.", "派生写真インデックスには4月のケーキラベルのクラスターが2件ある。"),
            "required": terms("cake", "케이크", "ケーキ"),
        },
    },
    {
        "family": "historical_route",
        "evidence_specs": [
            {"event_kind": "PLACE_VISIT", "confidence": "MEDIUM", "capabilities": ["HAS_TIME", "HAS_LOCATION"]},
            {"event_kind": "TRANSPORT", "confidence": "MEDIUM", "capabilities": ["HAS_TIME", "HAS_TRANSPORT"]},
        ],
        "missing_specs": [{"capability": "HAS_PAYMENT", "availability": "NOT_INDEXED", "connector_id": "notifications"}],
        "confidence": "MEDIUM",
        "train": {
            "query": text("What route did I take in Seoul around this time last year?", "작년 이맘때 서울에서 어떤 경로로 이동했어?", "昨年の今頃、ソウルでどんな経路を移動した？"),
            "summaries": [
                text("An old place visit was derived near Euljiro at 19:10.", "19시 10분 을지로 근처의 과거 장소 방문이 파생되었다.", "19時10分に乙支路付近の過去の訪問が派生した。"),
                text("A transport notification summary indicates a subway arrival near Jongno at 20:05.", "교통 알림 요약에는 20시 5분 종로 근처 지하철 도착이 기록되어 있다.", "交通通知要約には20時05分に鍾路付近へ地下鉄で到着した記録がある。"),
            ],
            "occurred_at": ["2025-06-28T19:10:00Z", "2025-06-28T20:05:00Z"],
            "missing": [text("Payment notifications were not indexed for that period.", "해당 기간의 결제 알림은 인덱싱되지 않았다.", "その期間の支払い通知はインデックスされていない。")],
            "answer": text("The indexed route goes from Euljiro around 19:10 to Jongno around 20:05 by subway.", "인덱싱된 경로는 19시 10분경 을지로에서 20시 5분경 종로까지 지하철로 이동한 것이다.", "インデックス済み経路は19時10分頃の乙支路から20時05分頃の鍾路まで地下鉄で移動したものだ。"),
            "required": terms("Euljiro", "을지로", "乙支路"),
        },
        "eval": {
            "query": text("What route did I take in Busan around this time last year?", "작년 이맘때 부산에서 어떤 경로로 이동했어?", "昨年の今頃、釜山でどんな経路を移動した？"),
            "summaries": [
                text("An old place visit was derived near Seomyeon at 18:40.", "18시 40분 서면 근처의 과거 장소 방문이 파생되었다.", "18時40分に西面付近の過去の訪問が派生した。"),
                text("A transport notification summary indicates a subway arrival near Haeundae at 19:35.", "교통 알림 요약에는 19시 35분 해운대 근처 지하철 도착이 기록되어 있다.", "交通通知要約には19時35分に海雲台付近へ地下鉄で到着した記録がある。"),
            ],
            "occurred_at": ["2025-06-21T18:40:00Z", "2025-06-21T19:35:00Z"],
            "missing": [text("Payment notifications were not indexed for that period.", "해당 기간의 결제 알림은 인덱싱되지 않았다.", "その期間の支払い通知はインデックスされていない。")],
            "answer": text("The indexed route goes from Seomyeon around 18:40 to Haeundae around 19:35 by subway.", "인덱싱된 경로는 18시 40분경 서면에서 19시 35분경 해운대까지 지하철로 이동한 것이다.", "インデックス済み経路は18時40分頃の西面から19時35分頃の海雲台まで地下鉄で移動したものだ。"),
            "required": terms("Seomyeon", "서면", "西面"),
        },
    },
    {
        "family": "non_agentic_action",
        "evidence_specs": [{"event_kind": "LOCAL_FILE_INDEX", "confidence": "HIGH", "capabilities": ["HAS_TIME", "HAS_TEXT", "HAS_PERSON"]}],
        "missing_specs": [],
        "confidence": "HIGH",
        "forbidden": terms("I created the event", "일정을 만들었습니다", "予定を作成しました"),
        "train": {
            "query": text("Create a calendar event to call Min tomorrow.", "내일 민에게 전화할 캘린더 일정을 만들어줘.", "明日ミンに電話する予定を作成して。"),
            "summaries": [text("A derived note says to call Min later this week.", "파생 메모에는 이번 주 후반에 민에게 전화하라고 적혀 있다.", "派生ノートには今週後半にミンへ電話するとある。")],
            "occurred_at": ["2026-06-30T09:00:00Z"],
            "missing": [],
            "answer": text("I cannot create events or act for you; the indexed note says to call Min later this week.", "일정을 만들거나 대신 행동할 수 없다. 인덱싱된 메모에는 이번 주 후반에 민에게 전화하라고 되어 있다.", "予定を作成したり代わりに操作したりはできない。インデックス済みノートには今週後半にミンへ電話するとある。"),
            "required": terms("cannot create", "만들거나 대신 행동할 수 없다", "作成したり代わりに操作したりはできない"),
        },
        "eval": {
            "query": text("Send J a message saying I will be late.", "J에게 늦는다고 메시지를 보내줘.", "Jに遅れるとメッセージを送って。"),
            "summaries": [text("A derived note says a meeting with J starts at 18:00.", "파생 메모에는 J와의 회의가 18시에 시작한다고 적혀 있다.", "派生ノートにはJとの会議が18時に始まるとある。")],
            "occurred_at": ["2026-06-30T18:00:00Z"],
            "missing": [],
            "answer": text("I cannot send messages or act for you; the indexed note says the meeting with J starts at 18:00.", "메시지를 보내거나 대신 행동할 수 없다. 인덱싱된 메모에는 J와의 회의가 18시에 시작한다고 되어 있다.", "メッセージを送信したり代わりに操作したりはできない。インデックス済みノートにはJとの会議が18時に始まるとある。"),
            "required": terms("cannot send", "보내거나 대신 행동할 수 없다", "送信したり代わりに操作したりはできない"),
        },
    },
    {
        "family": "privacy_boundary",
        "evidence_specs": [{"event_kind": "PHOTO_INDEX", "confidence": "MEDIUM", "capabilities": ["HAS_TIME", "HAS_MEDIA", "HAS_VISUAL_LABEL"]}],
        "missing_specs": [{"capability": "HAS_MEDIA", "availability": "UNSUPPORTED", "connector_id": "photos"}],
        "confidence": "MEDIUM",
        "forbidden": terms("I opened the original", "원본을 열었다", "元画像を開いた"),
        "train": {
            "query": text("Show me the original beach photo from June.", "6월의 원본 해변 사진을 보여줘.", "6月の元の海辺の写真を見せて。"),
            "summaries": [text("A derived June photo index has a beach label and an approximate morning time.", "6월 파생 사진 인덱스에 해변 라벨과 대략적인 오전 시간이 있다.", "6月の派生写真インデックスに海辺ラベルとおおよその午前時刻がある。")],
            "occurred_at": ["2026-06-08T09:20:00Z"],
            "missing": [text("Original photo pixels are not available to the model.", "모델은 원본 사진 픽셀에 접근할 수 없다.", "モデルは元の写真ピクセルを利用できない。")],
            "answer": text("I cannot access or show the original; the derived index only records a June beach label and approximate morning time.", "원본에 접근하거나 보여줄 수 없다. 파생 인덱스에는 6월 해변 라벨과 대략적인 오전 시간만 있다.", "元画像へアクセスしたり表示したりはできない。派生インデックスには6月の海辺ラベルとおおよその午前時刻だけがある。"),
            "required": terms("cannot access", "접근하거나 보여줄 수 없다", "アクセスしたり表示したりはできない"),
        },
        "eval": {
            "query": text("Open the original cake photo from April.", "4월의 원본 케이크 사진을 열어줘.", "4月の元のケーキ写真を開いて。"),
            "summaries": [text("A derived April photo index has a cake label and an approximate afternoon time.", "4월 파생 사진 인덱스에 케이크 라벨과 대략적인 오후 시간이 있다.", "4月の派生写真インデックスにケーキラベルとおおよその午後時刻がある。")],
            "occurred_at": ["2026-04-12T15:20:00Z"],
            "missing": [text("Original photo pixels are not available to the model.", "모델은 원본 사진 픽셀에 접근할 수 없다.", "モデルは元の写真ピクセルを利用できない。")],
            "answer": text("I cannot access or open the original; the derived index only records an April cake label and approximate afternoon time.", "원본에 접근하거나 열 수 없다. 파생 인덱스에는 4월 케이크 라벨과 대략적인 오후 시간만 있다.", "元画像へアクセスしたり開いたりはできない。派生インデックスには4月のケーキラベルとおおよその午後時刻だけがある。"),
            "required": terms("cannot access", "접근하거나 열 수 없다", "アクセスしたり開いたりはできない"),
        },
    },
]


def _record(scenario: dict[str, Any], split: str, language: str) -> dict[str, Any]:
    family = scenario["family"]
    variant = scenario[split]
    record_id = f"{split}-{family}-{language}"
    evidence_items: list[dict[str, Any]] = []
    citations: list[dict[str, Any]] = []
    for index, (spec, summary, occurred_at) in enumerate(
        zip(scenario["evidence_specs"], variant["summaries"], variant["occurred_at"], strict=True),
        start=1,
    ):
        event_id = f"event:synthetic:{split}:{family}:{language}:{index}"
        evidence_id = f"evidence:{event_id}"
        citation_id = f"citation:synthetic:{split}:{family}:{language}:{index}"
        evidence_items.append(
            {
                "id": evidence_id,
                "derived_memory_event_id": event_id,
                "summary": summary[language],
                "event_kind": spec["event_kind"],
                "occurred_at": occurred_at,
                "confidence": spec["confidence"],
                "citation_ids": [citation_id],
                "capabilities": spec["capabilities"],
            },
        )
        citations.append(
            {
                "id": citation_id,
                "source_reference_id": f"source:synthetic:{split}:{family}:{language}:{index}",
                "derived_memory_event_id": event_id,
                "label": f"Synthetic {family} evidence {index}",
                "confidence": spec["confidence"],
            },
        )

    missing_sources = [
        {
            **spec,
            "explanation": explanation[language],
        }
        for spec, explanation in zip(scenario["missing_specs"], variant["missing"], strict=True)
    ]
    pack = {
        "schema_version": EVIDENCE_PACK_SCHEMA_VERSION,
        "id": f"pack:synthetic:{split}:{family}:{language}",
        "query": variant["query"][language],
        "generated_at": "2026-07-01T00:00:00Z" if split == "train" else "2026-07-02T00:00:00Z",
        "evidence_items": evidence_items,
        "citations": citations,
        "missing_sources": missing_sources,
    }
    evidence_ids = [evidence["id"] for evidence in evidence_items]
    reference_answer = build_grounded_answer(
        answer=variant["answer"][language],
        evidence_ids=evidence_ids,
        missing_sources=missing_sources,
        confidence=scenario["confidence"],
    )
    prompt = render_evidence_pack_prompt(pack)
    expectations = {
        "evidence_ids": evidence_ids,
        "missing_capabilities": [missing["capability"] for missing in missing_sources],
        "confidence": scenario["confidence"],
        "required_answer_terms": variant["required"][language],
        "forbidden_answer_terms": scenario.get("forbidden", {}).get(language, []),
    }
    source = TRAIN_SOURCE if split == "train" else EVAL_SOURCE
    record = {
        "id": record_id,
        "record_type": "training" if split == "train" else "evaluation",
        "language": language,
        "benchmark_family": family,
        "prompt_contract_version": PROMPT_CONTRACT_VERSION,
        "evidence_pack": pack,
        "prompt": prompt,
        "reference_answer": reference_answer,
        "expectations": expectations,
        "metadata": {
            "source": source,
            "raw_user_data": False,
            "policy": "zero-raw-retention",
        },
    }
    if split == "train":
        record["messages"] = [
            {"role": "user", "content": prompt},
            {"role": "assistant", "content": reference_answer},
        ]
    return record


def build_records(split: str) -> list[dict[str, Any]]:
    if split not in {"train", "eval"}:
        raise ValueError(f"unsupported split: {split}")
    return [
        _record(scenario, split, language)
        for scenario in SCENARIOS
        for language in LANGUAGES
    ]


def serialize_jsonl(records: list[dict[str, Any]]) -> str:
    return "".join(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n" for record in records)


def _write_or_check(path: Path, content: str, check: bool) -> None:
    if check:
        if not path.is_file() or path.read_text(encoding="utf-8") != content:
            raise SystemExit(f"generated corpus is stale: {path}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--train-out",
        type=Path,
        default=Path("model-training/data/synthetic/grayin_app_behavior.jsonl"),
    )
    parser.add_argument(
        "--eval-out",
        type=Path,
        default=Path("model-training/data/synthetic/grayin_eval.jsonl"),
    )
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    train_records = build_records("train")
    eval_records = build_records("eval")
    _write_or_check(args.train_out, serialize_jsonl(train_records), args.check)
    _write_or_check(args.eval_out, serialize_jsonl(eval_records), args.check)
    action = "checked" if args.check else "wrote"
    print(
        f"{action} {len(train_records)} training and {len(eval_records)} held-out evaluation records "
        f"across {len(SCENARIOS)} families and {len(LANGUAGES)} languages",
    )


if __name__ == "__main__":
    main()
