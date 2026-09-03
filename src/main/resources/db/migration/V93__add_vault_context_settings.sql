-- =============================================================================================
-- Người dùng chọn phần nào trong vault được đưa vào ngữ cảnh AI.
--
-- ── Vì sao cần bảng này ─────────────────────────────────────────────────────────────────────
-- ExplanationService.loadVaultContext gửi tên file, thẻ và liên kết của tối đa 50 ghi chú vào
-- prompt của MỌI lần /explain. Trước bảng này, lựa chọn duy nhất người dùng có là nhị phân và
-- nằm ở chỗ khác hẳn: có token BIDIRECTIONAL hay không. Ai muốn AI thấy ghi chú kỹ thuật nhưng
-- không thấy nhật ký cá nhân thì chỉ còn cách ngừng đồng bộ toàn bộ vault.
--
-- Đây không phải chuyện tiện nghi. Plugin đẩy CẢ vault lên — nhật ký, ghi chú họp, mọi thứ — và
-- người dùng không có cách nào nói "phần này thì được, phần kia thì không".
--
-- ── Vì sao là một bảng riêng, không phải cột trên t_user_professional_profiles ──────────────
-- Hồ sơ nghề nghiệp là thứ ProfessionalProfileController PUT-toàn-phần: gửi thiếu trường nào là
-- trường đó thành null. Nhét cấu hình quyền riêng tư của vault vào đó nghĩa là mọi lần sửa hồ sơ
-- từ một client chưa biết tới các cột này sẽ âm thầm xoá bộ lọc — hỏng đúng theo hướng nguy hiểm
-- nhất: nhật ký cá nhân lặng lẽ quay lại prompt.
--
-- ── Vì sao rỗng nghĩa là "không lọc" ────────────────────────────────────────────────────────
-- Hàng chỉ được tạo khi người dùng thật sự cấu hình. Không có hàng, hoặc include_tags rỗng, đều
-- giữ nguyên hành vi cũ (lấy tất cả) — nên bản migration này không đổi kết quả của bất kỳ lần
-- /explain nào cho tới khi có người tự bật bộ lọc. exclude_tags được áp SAU include_tags.
-- =============================================================================================

CREATE TABLE socialapp.t_vault_context_settings (
    user_id INT PRIMARY KEY REFERENCES socialapp.t_users(id) ON DELETE CASCADE,

    -- Rỗng = không giới hạn. Có giá trị = CHỈ ghi chú mang ít nhất một trong các thẻ này.
    include_tags JSONB NOT NULL DEFAULT '[]'::jsonb,

    -- Luôn thắng include_tags. Một ghi chú vừa #tech vừa #private thì bị loại.
    exclude_tags JSONB NOT NULL DEFAULT '[]'::jsonb,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
