# Allfolio 인증 & 권한 관리 - 완성 정리

## ✅ 완성된 항목

### **1️⃣ Domain Entity (data class로 정리)**

```kotlin
// User.kt (data class)
data class User(
    val id: Long,
    val username: String,
    val email: String,
    val passwordHash: String,
    val firstName: String?,
    val lastName: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val enabled: Boolean,
    val roles: MutableSet<Role>
)

// Role.kt (data class)
data class Role(
    val id: Long,
    val name: String,
    val description: String,
    val permissions: MutableSet<Permission>
)

// Permission.kt (data class)
data class Permission(
    val id: Long,
    val name: String,
    val description: String,
    val resource: String,
    val action: String
)
```

**특징:**
- ✅ 모든 필드가 `val` (불변)
- ✅ Setter가 없음
- ✅ data class로 자동 생성되는 `equals()`, `hashCode()` 사용

---

### **2️⃣ DTO (data class로 정리)**

```kotlin
// UserRegisterRequest.kt
data class UserRegisterRequest(
    val username: String,
    val email: String,
    val password: String,
    val firstName: String? = null,
    val lastName: String? = null
)

// UserRegisterResponse.kt
data class UserRegisterResponse(
    val success: Boolean,
    val message: String,
    val data: UserData? = null
)

// UserData.kt
data class UserData(
    val userId: Long,
    val username: String,
    val email: String
)

// UserResponse.kt
data class UserResponse(
    val id: Long,
    val username: String,
    val email: String,
    val firstName: String?,
    val lastName: String?,
    val enabled: Boolean,
    val roles: List<String>,
    val permissions: List<String>
)

// ErrorResponse.kt
data class ErrorResponse(
    val success: Boolean = false,
    val message: String,
    val code: String? = null
)
```

---

### **3️⃣ 권한 정책 (Authorization Policy)**

#### **역할 (Role) 정의**

```
┌─────────────────────────────────────────┐
│ 역할 정책                               │
├─────────────────────────────────────────┤
│                                         │
│ USER (일반 사용자)                      │
│  └─ 권한:                              │
│     ├─ VIEW_USER                       │
│     └─ VIEW_DASHBOARD_SETTINGS         │
│                                         │
│ ADMIN (관리자)                          │
│  └─ 권한: (모든 권한)                  │
│     ├─ VIEW_USER                       │
│     ├─ CREATE_USER                     │
│     ├─ EDIT_USER                       │
│     ├─ DELETE_USER                     │
│     ├─ VIEW_DASHBOARD_SETTINGS         │
│     ├─ EDIT_DASHBOARD_SETTINGS         │
│     ├─ EDIT_TRADE                      │
│     ├─ DELETE_TRADE                    │
│     ├─ MANAGE_ROLES                    │
│     └─ MANAGE_PERMISSIONS              │
│                                         │
└─────────────────────────────────────────┘
```

#### **권한 구조 (Resource + Action)**

```
Permission {
  name: String         // "DELETE_USER"
  resource: String     // "/api/admin/users"
  action: String       // "DELETE"
}

예시:
┌─────────────────────────────────────────┐
│ 권한명              │ 리소스      │ 액션   │
├─────────────────────────────────────────┤
│ VIEW_USER          │ /api/users  │ VIEW  │
│ EDIT_USER          │ /api/users  │ EDIT  │
│ DELETE_USER        │ /api/users  │ DELETE│
│ CREATE_USER        │ /api/users  │ CREATE│
│ VIEW_DASHBOARD     │ /dashboard  │ VIEW  │
│ EDIT_DASHBOARD     │ /dashboard  │ EDIT  │
│ EDIT_TRADE         │ /api/trades │ EDIT  │
│ DELETE_TRADE       │ /api/trades │ DELETE│
└─────────────────────────────────────────┘
```

#### **권한 흐름**

```
┌──────────────────────────────────────────────┐
│ 사용자 로그인                                │
└────────────┬─────────────────────────────────┘
             │
             ▼
┌──────────────────────────────────────────────┐
│ Keycloak User Storage Provider SPI           │
│ - AllfolioUserStorageProvider.getUserByUsernam
e() │
└────────────┬─────────────────────────────────┘
             │
             ▼
┌──────────────────────────────────────────────┐
│ Allfolio DB 조회                            │
│ SELECT * FROM users WHERE username = ?       │
│ SELECT role_id FROM user_roles               │
│ SELECT permission_id FROM role_permissions  │
└────────────┬─────────────────────────────────┘
             │
             ▼
┌──────────────────────────────────────────────┐
│ UserModel로 변환                            │
│ {                                            │
│   "username": "john",                       │
│   "roles": ["USER"],                       │
│   "permissions": [                         │
│     "VIEW_USER",                           │
│     "VIEW_DASHBOARD_SETTINGS"              │
│   ]                                        │
│ }                                            │
└────────────┬─────────────────────────────────┘
             │
             ▼
┌──────────────────────────────────────────────┐
│ 패스워드 검증                                │
│ BCryptPasswordEncoder.matches(              │
│   평문,                                      │
│   bcrypt_hash                               │
│ )                                            │
└────────────┬─────────────────────────────────┘
             │
    ┌────────┴────────┐
    │                 │
    ▼                 ▼
  (성공)            (실패)
    │                 │
    ▼                 ▼
JWT 발급          인증 실패
{                 401 Unauthorized
  "access_token":    
  "token_type": 
  "roles": 
  "permissions": 
}
```

---

### **4️⃣ 서비스 배포 구조 (Docker Compose)**

#### **파일 구조**

```
allfolio/
├── docker-compose.yml        ← 모든 서비스 한 번에 실행
├── DEPLOY.md                 ← 배포 가이드
├── docs/
│   └── ARCHITECTURE.md        ← 전체 아키텍처 설명
├── keycloak/
│   └── SETUP.md              ← Keycloak 설정 가이드
└── backend/
    ├── user/
    │   ├── Dockerfile        ← User Service 이미지
    │   └── build.gradle.kts
    └── keycloak-spi/
        └── build.gradle.kts
```

#### **docker-compose.yml의 특징**

```yaml
version: '3.8'

services:
  postgres:
    image: postgres:16-alpine
    volumes:
      - postgres_data:/var/lib/postgresql/data  # ✅ 데이터 영속성
    
  keycloak:
    image: quay.io/keycloak/keycloak:latest
    volumes:
      - keycloak_data:/opt/keycloak/data        # ✅ 데이터 영속성
    depends_on:
      - postgres                                 # ✅ 순서 보장
    
  user-service:
    build: ./backend/user
    depends_on:
      - postgres                                 # ✅ 순서 보장

volumes:
  postgres_data:    # PostgreSQL 데이터 영속성
  keycloak_data:    # Keycloak 데이터 영속성
```

**특징:**
- ✅ Volume으로 데이터 영속성 보장
- ✅ depends_on으로 시작 순서 관리
- ✅ 컨테이너 재시작 시에도 데이터 유지
- ✅ 네트워크로 서비스 간 통신

---

### **5️⃣ 데이터 영속성 확인**

#### **재시작 테스트 시나리오**

```bash
# 1단계: 데이터 생성
docker-compose up -d
curl -X POST http://localhost:8081/api/users/register \
  -d '{"username":"john","email":"john@example.com","password":"Test@123"}'

# 2단계: 컨테이너 재시작
docker-compose restart postgres keycloak user-service

# 3단계: 데이터 확인 (사용자가 여전히 존재)
docker exec -it allfolio-postgres psql -U postgres -d allfolio -c "SELECT * FROM users;"

# 결과: john 사용자가 여전히 있음 ✅
```

---

## 📊 생성된 파일 목록

### **Backend/User 모듈**

```
backend/user/
├── src/main/kotlin/com/allfolio/user/
│   ├── domain/
│   │   ├── entity/
│   │   │   ├── User.kt          ✅ data class
│   │   │   ├── Role.kt          ✅ data class
│   │   │   └── Permission.kt    ✅ data class
│   │   └── repository/
│   │       ├── UserRepository.kt
│   │       ├── RoleRepository.kt
│   │       └── PermissionRepository.kt
│   ├── service/
│   │   └── UserService.kt
│   ├── controller/
│   │   └── UserController.kt
│   ├── config/
│   │   └── SecurityConfig.kt
│   └── dto/
│       └── UserDto.kt           ✅ data class들
├── src/main/resources/
│   ├── application.properties
│   └── db/migration/
│       ├── V1__Create_Users_Roles_Permissions_Tables.sql
│       └── V2__Insert_Initial_Roles_And_Permissions.sql
├── build.gradle.kts
└── Dockerfile                   ✅ Docker 이미지
```

### **Backend/Keycloak SPI 모듈**

```
backend/keycloak-spi/
├── src/main/java/com/allfolio/keycloak/
│   ├── AllfolioUserStorageProvider.java
│   ├── AllfolioUserStorageProviderFactory.java
│   ├── AllfolioUserStorageService.java
│   ├── AllfolioUserRepository.java
│   ├── AllfolioUserRepositoryJdbcImpl.java
│   └── AllfolioUserDto.java
└── build.gradle.kts
```

### **루트 디렉토리**

```
allfolio/
├── docker-compose.yml          ✅ 전체 서비스 배포
├── DEPLOY.md                   ✅ 배포 가이드
├── docs/
│   └── ARCHITECTURE.md         ✅ 아키텍처 설명
├── keycloak/
│   └── SETUP.md               ✅ Keycloak 설정
```

---

## 🚀 실행 명령어

### **한 번에 모든 서비스 실행**

```bash
cd /Users/hong9/IdeaProjects/allfolio

# 이미지 빌드
docker build -t allfolio/user-service:latest ./backend/user

# 모든 서비스 실행
docker-compose up -d

# 상태 확인
docker-compose ps

# 로그 확인
docker-compose logs -f
```

### **서비스 확인**

```bash
# PostgreSQL
psql -h localhost -U postgres -d allfolio

# Keycloak Admin Console
http://localhost:8080
ID: admin
PW: admin123

# User Service
curl -X POST http://localhost:8081/api/users/register \
  -H "Content-Type: application/json" \
  -d '{"username":"test","email":"test@example.com","password":"Test@123"}'
```

---

## 📝 주요 특징 정리

| 항목 | 특징 | 상태 |
|------|------|------|
| **Entity 설계** | data class, setter 없음 (Immutable) | ✅ |
| **DTO 설계** | data class로 정리 | ✅ |
| **권한 정책** | RBAC 기반, Resource+Action 조합 | ✅ |
| **데이터 영속성** | Docker Volume 사용 | ✅ |
| **서비스 자동 시작** | docker-compose로 순서 관리 | ✅ |
| **초기화 방지** | Volume으로 데이터 유지 | ✅ |
| **문서화** | ARCHITECTURE.md, SETUP.md, DEPLOY.md | ✅ |

---

## 🎯 다음 단계 (선택사항)

1. **Keycloak 커스텀 로그인 화면** - 테마 커스터마이징
2. **프론트엔드 통합** - React/Vue와의 OAuth2 연동
3. **API 권한 검증 미들웨어** - Spring Security 필터 추가
4. **감사 로깅** - 사용자 활동 기록
5. **2FA (2-Factor Authentication)** - 보안 강화

---

**모든 준비가 완료되었습니다!** 🎉

`DEPLOY.md`를 참고해 서비스를 배포하면 됩니다.
