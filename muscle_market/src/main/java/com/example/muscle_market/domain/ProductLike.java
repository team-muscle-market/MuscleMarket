package com.example.muscle_market.domain;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Entity
// https://velog.io/@hwan2da/JPA-Columnuniquetrue-UniqueConstraints
// UNIQUE (product_post_idx, user_idx) - 실제 DB 의미
@Table(name = "product_likes",
        uniqueConstraints = {
                // 유니크 조건의 이름, 컬럼 지정
                @UniqueConstraint(
                        name = "uk_product_user",
                        columnNames = {"product_post_idx", "user_idx"}
                )
        }
)
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductLike {
    @Id
    @Column(name = "product_likes_idx")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_post_idx")
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_idx")
    private User user;
}
