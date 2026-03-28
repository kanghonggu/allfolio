# Keycloak 초기 설정 가이드

## 🚀 시작 방법

### **Step 1: Docker Compose로 서비스 실행**

```bash
cd /Users/hong9/IdeaProjects/allfolio
docker-compose up -d
```

이 명령은 다음을 자동으로 시작합니다:
- PostgreSQL (포트 5432)
- Keycloak (포트 8080)
- User Service (포트 8081)

### **Step 2: Keycloak 관리자 콘솔 접속**

```
URL: http://localhost:8080
Admin Username: admin
Admin Password: admin123
```

---

## 🔧 Keycloak 설정 (수동)

### **1️⃣ Realm 생성**

1. Admin Console 접속
2. 좌측 상단 "Master" 클릭
3. "Create Realm" 버튼
4. Realm 정보 입력:
   ```
   Realm name: allfolio
   Enabled: ON
   ```
5. Create

### **2️⃣ User Storage Provider 등록**

1. 좌측 메뉴 → User Federation
2. "Create new provider" 클릭
3. "User Storage SPI" 선택
4. 설정:
   ```
   Name: allfolio-user-storage
   Priority: 0 (가장 높음)
   
   Allfolio DB 설정:
   - DB URL: jdbc:postgresql://postgres:5432/allfolio
   - DB Username: postgres
   - DB Password: postgres
   ```
5. Save

### **3️⃣ Client 설정 (프론트엔드 앱)**

1. 좌측 메뉴 → Clients
2. "Create Client" 클릭
3. Client 정보:
   ```
   Client ID: allfolio-frontend
   Client Protocol: openid-connect
   Access Type: Public
   Valid Redirect URIs: http://localhost:3000/*
   ```
4. Save

### **4️⃣ Client Roles 추가**

1. Client 선택 (allfolio-frontend)
2. "Roles" 탭
3. "Create Role" 클릭
4. Role 정보:
   ```
   Role Name: USER
   ```
5. Create

6. 같은 방식으로 ADMIN 역할도 생성

---

## 📊 Keycloak 데이터 구조

```
Realm: allfolio
├── User Federation
│   └── allfolio-user-storage (User Storage Provider SPI)
│       └── Allfolio PostgreSQL DB 연동
│
├── Clients
│   └── allfolio-frontend
│       ├── Client Protocol: openid-connect
│       └── Valid Redirect URIs: http://localhost:3000/*
│
├── Roles
│   ├── USER
│   └── ADMIN
│
└── Users
    └── Allfolio DB에서 조회 (User Storage Provider를 통해)
```

---

## 💾 데이터 영속성 확인

### **PostgreSQL 데이터 확인**

```bash
# PostgreSQL 컨테이너 접속
docker exec -it allfolio-postgres psql -U postgres -d allfolio

# 사용자 확인
SELECT * FROM users;

# 역할 확인
SELECT * FROM roles;

# 권한 확인
SELECT * FROM permissions;
```

### **Keycloak 데이터 확인**

```bash
# Keycloak 컨테이너 로그 확인
docker logs allfolio-keycloak

# 컨테이너 재시작 테스트
docker restart allfolio-keycloak

# 데이터가 유지되는지 확인
# Admin Console에서 Users 확인
```

---

## 🔄 로그인 테스트

### **Step 1: 사용자 회원가입 (User Service)**

```bash
curl -X POST http://localhost:8081/api/users/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "email": "test@example.com",
    "password": "Test@1234",
    "firstName": "Test",
    "lastName": "User"
  }'
```

### **Step 2: Keycloak에서 로그인**

```bash
curl -X POST http://localhost:8080/realms/allfolio/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d 'client_id=allfolio-frontend&username=testuser&password=Test@1234&grant_type=password&client_secret=your_client_secret'
```

### **응답 예시**

```json
{
  "access_token": "eyJhbGc...",
  "expires_in": 300,
  "refresh_expires_in": 1800,
  "refresh_token": "eyJhbGc...",
  "token_type": "Bearer",
  "id_token": "eyJhbGc...",
  "not-before-policy": 0,
  "session_state": "...",
  "scope": "profile email"
}
```

JWT 토큰 디코딩:
```bash
# https://jwt.io 에서 확인 또는

echo "YOUR_JWT_TOKEN" | jq -R 'split(".")[1] | @base64d | fromjson'
```

---

## 🛑 문제 해결

### **1. Keycloak이 시작되지 않는 경우**

```bash
# 로그 확인
docker logs allfolio-keycloak

# 포트 충돌 확인
lsof -i :8080

# 컨테이너 강제 종료 후 재시작
docker rm -f allfolio-keycloak
docker-compose up -d keycloak
```

### **2. PostgreSQL 연결 오류**

```bash
# PostgreSQL 상태 확인
docker exec allfolio-postgres pg_isready -U postgres

# 데이터베이스 확인
docker exec -it allfolio-postgres psql -U postgres -c "\l"
```

### **3. User Storage Provider 설정 문제**

```bash
# Keycloak 로그에서 SPI 관련 에러 확인
docker logs allfolio-keycloak | grep -i "provider\|SPI"

# Allfolio User Service가 실행 중인지 확인
docker ps | grep allfolio
```

### **4. 데이터 초기화하고 싶을 때**

```bash
# 볼륨 삭제 (⚠️ 모든 데이터 삭제됨)
docker-compose down -v

# 다시 시작
docker-compose up -d
```

---

## 📝 환경 변수

### **docker-compose.yml에서 설정 가능한 변수**

```yaml
# Keycloak
KC_DB: postgres                              # 데이터베이스 종류
KC_DB_URL: jdbc:postgresql://...            # DB 연결 URL
KEYCLOAK_ADMIN: admin                        # Admin 사용자명
KEYCLOAK_ADMIN_PASSWORD: admin123            # Admin 패스워드

# Spring Boot User Service
SPRING_DATASOURCE_URL: jdbc:postgresql://... # 데이터베이스 URL
SPRING_DATASOURCE_USERNAME: postgres         # DB 사용자명
SPRING_DATASOURCE_PASSWORD: postgres         # DB 패스워드
```

---

## 🎯 정리

| 구성 요소 | 용도 | 데이터 저장 | 포트 |
|---------|------|-----------|------|
| PostgreSQL | 데이터베이스 | `postgres_data` volume | 5432 |
| Keycloak | 인증 서버 | `keycloak_data` volume | 8080 |
| User Service | 회원가입/사용자 관리 | PostgreSQL | 8081 |

**Volume을 사용하므로 컨테이너 재시작 시에도 데이터가 유지됩니다!** ✅
