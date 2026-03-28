# Allfolio 사용자 인증 & 권한 관리 아키텍처

## 📊 시스템 전체 흐름도

```
┌──────────────────┐
│   클라이언트      │
│  Web/Mobile App  │
└────────┬─────────┘
         │
         ├──────────────────────────────┐
         │                              │
         ▼                              ▼
    ┌────────────┐            ┌──────────────────┐
    │  회원가입   │            │  로그인          │
    │   API      │            │  (Keycloak)     │
    └─────┬──────┘            └────────┬─────────┘
          │                           │
          ▼                           ▼
    ┌─────────────────────────────────────────┐
    │   Allfolio Backend (User 모듈)          │
    │                                         │
    │  Controller:                           │
    │  └─ POST /api/users/register          │
    │                                         │
    │  Service:                              │
    │  └─ UserService.registerUser()        │
    │     - 패스워드 bcrypt 해싱             │
    │     - 중복 검사                        │
    │     - User 역할 자동 할당              │
    │                                         │
    │  Domain:                               │
    │  ├─ User (data class)                 │
    │  ├─ Role (data class)                 │
    │  └─ Permission (data class)           │
    │                                         │
    │  Repository:                           │
    │  └─ JpaRepository Interface            │
    └─────────────────────────────────────────┘
          │
          ▼
    ┌─────────────────────────────────────────┐
    │  Keycloak (인증 서버)                   │
    │                                         │
    │  User Storage Provider SPI:            │
    │  └─ AllfolioUserStorageProvider       │
    │     - getUserByUsername()              │
    │     - getUserByEmail()                │
    │     - isValid() (패스워드 검증)        │
    │                                         │
    │  JWT 토큰 발급:                        │
    │  {                                     │
    │    "sub": "user_id",                  │
    │    "preferred_username": "john",     │
    │    "roles": ["USER"],                │
    │    "permissions": [...]              │
    │  }                                     │
    └─────────────────────────────────────────┘
          │
          ▼
    ┌─────────────────────────────────────────┐
    │   PostgreSQL Database                   │
    │                                         │
    │  users                                  │
    │  ├─ id, username, email                │
    │  ├─ password_hash (bcrypt)            │
    │  └─ roles (M2M via user_roles)        │
    │                                         │
    │  roles                                  │
    │  ├─ USER (일반 사용자)                │
    │  └─ ADMIN (관리자)                    │
    │                                         │
    │  permissions                            │
    │  ├─ VIEW_USER                         │
    │  ├─ EDIT_USER                         │
    │  ├─ DELETE_USER                       │
    │  ├─ VIEW_DASHBOARD_SETTINGS           │
    │  ├─ EDIT_DASHBOARD_SETTINGS           │
    │  ├─ EDIT_TRADE                        │
    │  └─ DELETE_TRADE                      │
    └─────────────────────────────────────────┘
```

---

## 🔐 권한 정책 (Authorization Policy)

### **1. 역할 (Role) 정의**

| 역할 | 설명 | 할당 시점 |
|------|------|---------|
| **USER** | 일반 사용자 | 회원가입 시 자동 할당 |
| **ADMIN** | 관리자 | 관리자가 수동으로 할당 |

### **2. 권한 (Permission) 정의**

#### **USER 역할이 가진 권한**
```
- VIEW_USER
  ├─ Resource: /api/admin/users
  ├─ Action: VIEW
  └─ 설명: 사용자 정보 조회

- VIEW_DASHBOARD_SETTINGS
  ├─ Resource: /dashboard/settings
  ├─ Action: VIEW
  └─ 설명: 대시보드 설정 조회
```

#### **ADMIN 역할이 가진 권한 (모든 권한)**
```
- VIEW_USER           → /api/admin/users, VIEW
- CREATE_USER         → /api/admin/users, CREATE
- EDIT_USER           → /api/admin/users, EDIT
- DELETE_USER         → /api/admin/users, DELETE

- VIEW_DASHBOARD_SETTINGS    → /dashboard/settings, VIEW
- EDIT_DASHBOARD_SETTINGS    → /dashboard/settings, EDIT

- EDIT_TRADE          → /api/admin/trades, EDIT
- DELETE_TRADE        → /api/admin/trades, DELETE

- MANAGE_ROLES        → /api/admin/roles, EDIT
- MANAGE_PERMISSIONS  → /api/admin/permissions, EDIT
```

### **3. 권한 검증 방식**

#### **방식 1: JWT 클레임 기반 (프론트엔드)**
```javascript
// 클라이언트가 JWT 토큰에서 권한 확인
const token = localStorage.getItem('token');
const decoded = jwt_decode(token);
const permissions = decoded.permissions;

if (permissions.includes('DELETE_USER')) {
    // 사용자 삭제 버튼 표시
    showDeleteButton();
}
```

#### **방식 2: 백엔드 검증**
```kotlin
@RestController
class AdminController(
    private val userService: UserService
) {
    @DeleteMapping("/api/admin/users/{userId}")
    @PreAuthorize("hasPermission('DELETE_USER')")  // Spring Security
    fun deleteUser(@PathVariable userId: Long) {
        userService.deleteUser(userId)
    }
}
```

---

## 🔄 인증 흐름 상세

### **Step 1: 회원가입**

```
POST /api/users/register

요청:
{
  "username": "john_doe",
  "email": "john@example.com",
  "password": "password123",
  "firstName": "John",
  "lastName": "Doe"
}

처리:
1. UserController.register()
2. UserService.registerUser()
   ├─ username 중복 검사
   ├─ email 중복 검사
   ├─ 패스워드 bcrypt 해싱
   ├─ User 엔티티 생성 (data class)
   ├─ USER 역할 자동 할당
   └─ DB 저장 (users, user_roles 테이블)

응답 (201 Created):
{
  "success": true,
  "message": "User registered successfully",
  "data": {
    "userId": 1,
    "username": "john_doe",
    "email": "john@example.com"
  }
}
```

### **Step 2: 로그인 (Keycloak)**

```
1. 사용자가 Keycloak 로그인 화면에서 username/password 입력

2. Keycloak이 User Storage Provider SPI 호출:
   AllfolioUserStorageProvider.getUserByUsername("john_doe")
   
3. SPI 구현체가 Allfolio DB 조회:
   - SELECT * FROM users WHERE username = 'john_doe'
   - SELECT role_id FROM user_roles WHERE user_id = ?
   - SELECT permission_id FROM role_permissions WHERE role_id = ?
   
4. Allfolio DB의 데이터를 UserModel로 변환:
   {
     username: "john_doe",
     email: "john@example.com",
     roles: ["USER"],
     permissions: ["VIEW_USER", "VIEW_DASHBOARD_SETTINGS"]
   }

5. 패스워드 검증:
   AllfolioUserStorageProvider.isValid(...)
   └─ BCryptPasswordEncoder.matches(평문, bcrypt해시)
   
6. 패스워드가 맞으면 JWT 토큰 발급:
   {
     "access_token": "eyJhbGc...",
     "token_type": "Bearer",
     "expires_in": 3600
   }
   
   JWT 디코딩:
   {
     "sub": "john_doe",
     "preferred_username": "john_doe",
     "email": "john@example.com",
     "roles": ["USER"],
     "permissions": ["VIEW_USER", "VIEW_DASHBOARD_SETTINGS"],
     "iat": 1234567890,
     "exp": 1234571490
   }
```

### **Step 3: 인가 (Authorization)**

```
클라이언트가 JWT를 헤더에 담아 API 호출:

GET /api/admin/trades
Authorization: Bearer eyJhbGc...

백엔드에서 권한 검증:
1. JWT 토큰 검증
2. claims의 permissions 확인
3. 필요한 권한이 있으면 요청 처리
4. 없으면 403 Forbidden 응답
```

---

## 📦 생성된 파일 구조

### **Backend/User 모듈**

```
backend/user/src/main/kotlin/com/allfolio/user/
│
├── domain/
│   ├── entity/
│   │   ├── User.kt (data class)
│   │   ├── Role.kt (data class)
│   │   └── Permission.kt (data class)
│   └── repository/
│       ├── UserRepository.kt (JpaRepository)
│       ├── RoleRepository.kt (JpaRepository)
│       └── PermissionRepository.kt (JpaRepository)
│
├── service/
│   └── UserService.kt
│
├── controller/
│   └── UserController.kt
│
├── config/
│   └── SecurityConfig.kt (BCryptPasswordEncoder)
│
└── dto/
    └── UserDto.kt
        ├── UserRegisterRequest (data class)
        ├── UserRegisterResponse (data class)
        ├── UserData (data class)
        ├── UserResponse (data class)
        └── ErrorResponse (data class)
```

### **Backend/Keycloak SPI 모듈**

```
backend/keycloak-spi/src/main/java/com/allfolio/keycloak/
│
├── AllfolioUserStorageProvider.java
│   ├── getUserByUsername()
│   ├── getUserById()
│   ├── getUserByEmail()
│   └── isValid() (패스워드 검증)
│
├── AllfolioUserStorageProviderFactory.java
│   └── DataSource 초기화
│
├── AllfolioUserStorageService.java
│   └── Allfolio DB 조회 & 검증
│
├── AllfolioUserRepository.java (인터페이스)
│
├── AllfolioUserRepositoryJdbcImpl.java
│   ├── findByUsername()
│   ├── findById()
│   ├── findByEmail()
│   ├── getRoles()
│   └── getPermissions()
│
└── AllfolioUserDto.java (Immutable)
```

### **Database**

```
PostgreSQL
│
├── users
│   ├── id (PK)
│   ├── username (UNIQUE)
│   ├── email (UNIQUE)
│   ├── password_hash
│   ├── first_name
│   ├── last_name
│   ├── enabled
│   ├── created_at
│   └── updated_at
│
├── roles
│   ├── id (PK)
│   ├── name (UNIQUE)
│   └── description
│
├── permissions
│   ├── id (PK)
│   ├── name (UNIQUE)
│   ├── description
│   ├── resource
│   └── action
│
├── user_roles (M2M)
│   ├── user_id (FK)
│   └── role_id (FK)
│
└── role_permissions (M2M)
    ├── role_id (FK)
    └── permission_id (FK)
```

---

## 🎯 핵심 특징

| 특징 | 설명 |
|------|------|
| **Immutable 설계** | Entity와 DTO가 data class로 정의, setter 없음 |
| **M2M 관계** | User-Role, Role-Permission 다대다 관계 |
| **JWT 기반 인증** | Keycloak이 권한 정보 포함한 JWT 발급 |
| **유연한 권한** | resource + action 조합으로 무한정 확장 |
| **범용성** | 다른 프로젝트에도 재사용 가능한 설계 |
| **bcrypt 보안** | 패스워드는 bcrypt로 안전하게 해싱 |

---

## 🔗 권한 조회 예시

**사용자 "john_doe"의 권한 조회 경로:**

```sql
-- Step 1: john_doe의 user_id 조회
SELECT id FROM users WHERE username = 'john_doe';
-- Result: 1

-- Step 2: john_doe가 가진 역할 조회
SELECT r.name 
FROM roles r
JOIN user_roles ur ON r.id = ur.role_id
WHERE ur.user_id = 1;
-- Result: USER

-- Step 3: USER 역할이 가진 권한 조회
SELECT p.name, p.resource, p.action
FROM permissions p
JOIN role_permissions rp ON p.id = rp.permission_id
JOIN roles r ON r.id = rp.role_id
WHERE r.name = 'USER';
-- Result:
-- VIEW_USER | /api/admin/users | VIEW
-- VIEW_DASHBOARD_SETTINGS | /dashboard/settings | VIEW
```
