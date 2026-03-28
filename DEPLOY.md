# Allfolio 서비스 배포 가이드

## 📋 사전 요구사항

- Docker & Docker Compose
- Java 21
- Gradle

---

## 🚀 실행 방법

### **방법 1: 개발 환경 (로컬)**

```bash
cd /Users/hong9/IdeaProjects/allfolio

# PostgreSQL만 실행
docker-compose up -d postgres

# User Service 실행 (Gradle)
cd backend/user
gradle bootRun

# Keycloak 실행 (선택)
cd ../..
docker-compose up -d keycloak
```

### **방법 2: 전체 서비스 (Docker Compose)**

```bash
cd /Users/hong9/IdeaProjects/allfolio

# 빌드
docker build -t allfolio/user-service:latest ./backend/user

# 실행
docker-compose up -d

# 상태 확인
docker-compose ps

# 로그 확인
docker-compose logs -f
```

---

## ✅ 서비스 확인

### **1. PostgreSQL 확인**

```bash
# 연결 테스트
psql -h localhost -U postgres -d allfolio -c "SELECT VERSION();"

# 테이블 확인
docker exec -it allfolio-postgres psql -U postgres -d allfolio -c "\dt"
```

### **2. Keycloak 확인**

```bash
# Admin Console 접속
http://localhost:8080

# 로그인
ID: admin
PW: admin123

# Health Check
curl http://localhost:8080/health/ready
```

### **3. User Service 확인**

```bash
# 회원가입 테스트
curl -X POST http://localhost:8081/api/users/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "email": "test@example.com",
    "password": "Test@1234"
  }'
```

---

## 🔄 데이터 영속성 확인

### **컨테이너 재시작 후 데이터 유지 테스트**

```bash
# 1. 사용자 생성
curl -X POST http://localhost:8081/api/users/register \
  -H "Content-Type: application/json" \
  -d '{"username":"john","email":"john@example.com","password":"Test@1234"}'

# 2. 컨테이너 재시작
docker-compose restart postgres keycloak user-service

# 3. 데이터 확인 (사용자가 여전히 있어야 함)
docker exec -it allfolio-postgres psql -U postgres -d allfolio -c "SELECT * FROM users;"
```

**결과: 사용자 정보가 여전히 존재함 ✅**

---

## 📊 시스템 아키텍처

```
┌─────────────────────────────────────────┐
│        Docker Network: allfolio-network │
├─────────────────────────────────────────┤
│                                         │
│  ┌──────────────┐   ┌──────────────┐  │
│  │  PostgreSQL  │   │  Keycloak    │  │
│  │  (Port 5432) │   │  (Port 8080) │  │
│  └──────┬───────┘   └───────┬──────┘  │
│         │                   │          │
│         └─────────┬─────────┘          │
│                   │                    │
│           ┌───────▼────────┐           │
│           │  User Service  │           │
│           │  (Port 8081)   │           │
│           └────────────────┘           │
│                                         │
└─────────────────────────────────────────┘

Volumes:
├── postgres_data      → PostgreSQL 데이터
└── keycloak_data      → Keycloak 데이터
```

---

## 🛑 서비스 중지

```bash
# 모든 서비스 중지
docker-compose stop

# 모든 서비스 종료
docker-compose down

# 모든 데이터 삭제 (⚠️ 주의)
docker-compose down -v
```

---

## 🔧 트러블슈팅

### **포트 충돌**

```bash
# 포트 확인
lsof -i :8080  # Keycloak
lsof -i :8081  # User Service
lsof -i :5432  # PostgreSQL

# 프로세스 강제 종료
kill -9 <PID>
```

### **컨테이너 로그 확인**

```bash
# 특정 서비스 로그
docker logs allfolio-postgres
docker logs allfolio-keycloak
docker logs allfolio-user-service

# 실시간 로그
docker-compose logs -f keycloak
```

### **네트워크 문제**

```bash
# 네트워크 확인
docker network ls
docker network inspect allfolio-network

# 서비스 간 연결 테스트
docker exec allfolio-user-service curl http://postgres:5432
```

---

## 📝 환경 설정

### **개발 환경**

```properties
# application.properties
spring.datasource.url=jdbc:postgresql://localhost:5432/allfolio
spring.datasource.username=postgres
spring.datasource.password=postgres
spring.jpa.hibernate.ddl-auto=validate
```

### **프로덕션 환경**

```properties
# application.properties
spring.datasource.url=jdbc:postgresql://postgres:5432/allfolio
spring.datasource.username=postgres
spring.datasource.password=postgres
spring.jpa.hibernate.ddl-auto=validate
```

---

## 🎯 정리

| 단계 | 명령어 | 목적 |
|------|--------|------|
| 빌드 | `docker build -t allfolio/user-service:latest ./backend/user` | 이미지 생성 |
| 실행 | `docker-compose up -d` | 서비스 시작 |
| 확인 | `docker-compose ps` | 상태 확인 |
| 로그 | `docker-compose logs -f` | 실시간 로그 |
| 중지 | `docker-compose stop` | 서비스 중지 |
| 삭제 | `docker-compose down` | 서비스 종료 |

**데이터는 Docker Volume에 저장되므로 컨테이너 재시작 시에도 유지됩니다!** ✅
