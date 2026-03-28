# Domain & DTO 상세 코멘트 및 Validation 가이드

## 📚 추가된 내용

### **1️⃣ Domain Entity 코멘트 및 Validation**

#### **User.kt**
- ✅ 각 필드에 대한 상세한 설명
- ✅ 저장되는 데이터 형식 (예: bcrypt 해시)
- ✅ Validation 규칙 명시
  - `@NotBlank`: 빈 값 불허
  - `@Size`: 길이 제한
  - `@Pattern`: 정규식 검증
  - `@Email`: 이메일 형식
  - `@NotNull`, `@NotEmpty`: null 또는 빈 컬렉션 불허
- ✅ 유틸리티 메서드 추가
  - `getFullName()`: 전체 이름 반환
  - `hasRole()`: 특정 역할 보유 확인

#### **Role.kt**
- ✅ 역할의 개념과 용도 설명
- ✅ 사전 정의된 역할 목록
- ✅ Validation 규칙
  - `@Pattern("^[A-Z][A-Z0-9_]*$")`: 영문 대문자 + 언더스코어
- ✅ 유틸리티 메서드
  - `hasPermission()`: 특정 권한 보유 확인
  - `canAccess()`: 리소스/액션 접근 가능 확인

#### **Permission.kt**
- ✅ 권한의 구조 설명 (Resource + Action)
- ✅ 네이밍 규칙 명시 (<ACTION>_<RESOURCE>)
- ✅ 표준 액션 설명 (VIEW, CREATE, EDIT, DELETE)
- ✅ 커스텀 액션 예시
- ✅ Validation 규칙
- ✅ 유틸리티 메서드
  - `getFullDescription()`: 전체 설명
  - `matches()`: 리소스/액션 일치 확인

---

### **2️⃣ DTO Validation**

#### **UserRegisterRequest.kt**
```kotlin
// Validation 규칙

@NotBlank(message = "Username is required and cannot be blank")
@Size(min = 3, max = 20, message = "Username must be between 3 and 20 characters")
@Pattern(regexp = "^[a-zA-Z0-9_]{3,20}$", message = "Username can only contain letters, numbers, and underscores")
val username: String

@NotBlank(message = "Email is required and cannot be blank")
@Email(message = "Email should be valid")
@Size(max = 255, message = "Email must not exceed 255 characters")
val email: String

@NotBlank(message = "Password is required and cannot be blank")
@Size(min = 8, max = 50, message = "Password must be between 8 and 50 characters")
val password: String

@Size(max = 100, message = "First name must not exceed 100 characters")
val firstName: String? = null

@Size(max = 100, message = "Last name must not exceed 100 characters")
val lastName: String? = null
```

**특징:**
- ✅ 모든 필드에 명확한 에러 메시지
- ✅ 선택사항 필드는 null 허용
- ✅ 비밀번호는 최소 8자 (강한 비밀번호 권장)

---

#### **UserData.kt**
```kotlin
@NotNull(message = "User ID cannot be null")
@Positive(message = "User ID must be a positive number")
val userId: Long

@NotBlank(message = "Username cannot be blank")
val username: String

@NotBlank(message = "Email cannot be blank")
@Email(message = "Email should be valid")
val email: String
```

---

#### **UserRegisterResponse.kt**
```kotlin
@NotNull(message = "Success status cannot be null")
val success: Boolean

@NotBlank(message = "Message cannot be blank")
@Size(min = 1, max = 500, message = "Message must be between 1 and 500 characters")
val message: String

val data: UserData? = null  // Optional
```

---

#### **UserResponse.kt**
```kotlin
@NotNull(message = "User ID cannot be null")
@Positive(message = "User ID must be a positive number")
val id: Long

@NotBlank(message = "Username cannot be blank")
val username: String

@NotBlank(message = "Email cannot be blank")
@Email(message = "Email should be valid")
val email: String

@NotEmpty(message = "User must have at least one role")
@Size(min = 1, message = "Roles list cannot be empty")
val roles: List<String>

val permissions: List<String>
```

**특징:**
- ✅ 사용자가 최소 1개의 역할 필수
- ✅ 권한 목록은 선택사항 (비어있을 수 있음)

---

#### **ErrorResponse.kt**
```kotlin
@NotNull(message = "Success status cannot be null")
val success: Boolean = false

@NotBlank(message = "Error message cannot be blank")
@Size(min = 1, max = 500, message = "Message must be between 1 and 500 characters")
val message: String

@Size(max = 50, message = "Error code must not exceed 50 characters")
val code: String? = null
```

---

### **3️⃣ Service Layer Validation**

#### **UserService.registerUser()**
```kotlin
fun registerUser(
    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 20)
    @Pattern(regexp = "^[a-zA-Z0-9_]{3,20}$")
    username: String,

    @NotBlank(message = "Email is required")
    @Email(message = "Email format is invalid")
    email: String,

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 50)
    password: String,

    @Size(max = 100)
    firstName: String? = null,

    @Size(max = 100)
    lastName: String? = null
): User
```

**특징:**
- ✅ 메서드 파라미터 레벨 Validation
- ✅ @Validated 클래스에서만 작동
- ✅ MethodArgumentNotValidException 발생 시 Spring이 처리

---

### **4️⃣ Controller Layer Validation**

#### **UserController.register()**
```kotlin
@PostMapping("/register")
fun register(
    @Valid @RequestBody request: UserRegisterRequest
): ResponseEntity<UserRegisterResponse>
```

**특징:**
- ✅ @Valid로 DTO 유효성 검사 수행
- ✅ 검사 실패 시 Spring이 자동으로 400 Bad Request 반환
- ✅ try-catch로 비즈니스 로직 예외 처리

---

## 🔍 Validation 계층도

```
Request JSON
    ↓
Jackson (자동 변환)
    ↓
UserRegisterRequest (DTO)
    ↓
@Valid 검사 (Controller)
    ↓
검사 실패 → 400 Bad Request (Spring 자동 처리)
검사 성공 → UserService.registerUser() 호출
    ↓
메서드 파라미터 Validation (Service)
    ↓
검사 실패 → ConstraintViolationException
검사 성공 → 비즈니스 로직 실행
    ↓
User 엔티티 생성
    ↓
Entity Validation (JPA)
    ↓
DB 저장 또는 에러
```

---

## 📋 Validation 규칙 정리

| Annotation | 용도 | 예시 |
|-----------|------|------|
| `@NotNull` | null 불허 | 필수 필드 |
| `@NotBlank` | 빈 값/공백 불허 | 사용자명, 이메일 |
| `@NotEmpty` | 비어있지 않은 컬렉션 | 역할 목록 |
| `@Size` | 문자열/컬렉션 길이 | `@Size(min=3, max=20)` |
| `@Pattern` | 정규식 검증 | `@Pattern(regexp="^[a-zA-Z0-9_]*$")` |
| `@Email` | 이메일 형식 | john@example.com |
| `@Positive` | 양수만 허용 | ID > 0 |
| `@Min/@Max` | 숫자 범위 | 나이: `@Min(0) @Max(150)` |

---

## 🎯 에러 처리 흐름

### **클라이언트 요청**
```json
POST /api/users/register
{
  "username": "jo",  // 너무 짧음 (최소 3자)
  "email": "invalid",  // 유효하지 않은 이메일
  "password": "short"  // 너무 짧음 (최소 8자)
}
```

### **Spring의 Validation 처리**

1️⃣ **DTO Validation 실패**
   - Spring이 자동으로 MethodArgumentNotValidException 발생
   - 400 Bad Request 반환 (자동)

```json
HTTP 400 Bad Request
{
  "timestamp": "2024-01-18T12:00:00.000Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "errors": [
    {
      "field": "username",
      "message": "Username must be between 3 and 20 characters"
    },
    {
      "field": "email",
      "message": "Email should be valid"
    }
  ]
}
```

2️⃣ **비즈니스 로직 에러** (Validation 통과 후)
   - Controller의 try-catch에서 처리
   - 명확한 에러 메시지 반환

```json
HTTP 400 Bad Request
{
  "success": false,
  "message": "Username already exists: john_doe"
}
```

3️⃣ **서버 에러**
   - 예상 밖의 예외 처리
   - 500 Internal Server Error 반환

```json
HTTP 500 Internal Server Error
{
  "success": false,
  "message": "Internal server error: Database connection failed"
}
```

---

## 💡 사용 팁

### **클라이언트에서 Validation 결과 처리**

```javascript
// JavaScript/Fetch 예시
const response = await fetch('/api/users/register', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    username: 'john_doe',
    email: 'john@example.com',
    password: 'SecurePass@123'
  })
});

const data = await response.json();

if (response.status === 400) {
  // Validation 실패
  if (data.errors) {
    // Spring의 자동 Validation 에러
    data.errors.forEach(error => {
      console.error(`${error.field}: ${error.message}`);
    });
  } else if (data.message) {
    // 비즈니스 로직 에러
    console.error(data.message);
  }
} else if (response.status === 201) {
  // 성공
  console.log('User created:', data.data);
} else if (response.status === 500) {
  // 서버 에러
  console.error('Server error:', data.message);
}
```

---

## ✅ 체크리스트

- ✅ 모든 Domain Entity에 상세 코멘트 추가
- ✅ 모든 DTO에 Validation Annotation 추가
- ✅ 모든 필드에 명확한 에러 메시지
- ✅ 유틸리티 메서드 추가 (getFullName, hasRole, canAccess 등)
- ✅ Service Layer에 파라미터 Validation
- ✅ Controller Layer에서 에러 처리
- ✅ bcrypt 패스워드 해싱 설명
- ✅ 권한 정책 명시

---

**모든 준비가 완료되었습니다!** 🎉

이제 프로젝트를 실행할 때:
1. DTO의 Validation이 자동으로 수행됨
2. 에러 메시지가 명확함
3. 코드가 쉽게 이해됨
4. 유지보수가 용이함
