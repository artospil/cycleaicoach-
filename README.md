# Cycle AI Coach

개인 체력에 맞춘 8주 사이클링 훈련 계획과 춘천 코스 지도, GPS 라이딩 기록을 제공하는 앱입니다. (v1.1B)

## 주요 기능
- 훈련 계획 자동 생성: 파워미터(FTP) · 심박계(카보넨 5존) · 칼로리 목표 3가지 방식
- 가민 워크아웃 파일(.FIT) 내보내기
- 춘천 코스 지도: 획득고도·경사, 도로 표지판·주의 안내
- 라이딩 모드: GPS 기록, 거리·시간·칼로리, GPX 저장 (안드로이드 앱은 화면이 꺼져도 기록)

## 폴더 구성
| 경로 | 내용 |
|---|---|
| `index.html`, `presets.json`, `chuncheon_base.json` | 웹 앱 (GitHub Pages로 바로 실행) |
| `android/` | 안드로이드 스튜디오 프로젝트 (WebView 앱) |
| `변경내역.md` | 1.0 대비 변경 내역 |

## 실행
- 웹: 저장소 GitHub Pages 주소로 접속
- 안드로이드: `android` 폴더를 Android Studio로 열고 Build → Build APK(s). 자세한 안내는 `android/빌드_안내.txt`
