package com.example.muscle_market.service;

import com.example.muscle_market.domain.Product;
import com.example.muscle_market.domain.ProductImage;
import com.example.muscle_market.domain.Sport;
import com.example.muscle_market.domain.User;
import com.example.muscle_market.dto.*;
import com.example.muscle_market.enums.TransactionStatus;
import com.example.muscle_market.repository.*;
import org.springframework.cglib.core.Local;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final ProductLikeRepository productLikeRepository;
    private final UserRepository userRepository;
    private final SportRepository sportRepository;
    private final ProductImageService productImageService;

    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // 제품 상세 정보 조회
    @Transactional // 조회수 증가 때문에 트랜잭션을 ReadOnly = false로 설정
    public ProductDetailDto getProductDetail(Long productId, Long currentUserId) {
        // Product 엔티티 조회
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("해당 제품을 찾을 수 없습니다. ID: " + productId));

        // 삭제된 게시물
        if (product.getStatus() == TransactionStatus.DELETE) {
            throw new IllegalArgumentException("삭제된 게시물입니다.");
        }

        // 조회수 1 증가
        product.setViews();

        // 좋아요 수 계산
        long likeCount = productLikeRepository.countByProductId(productId);

        // 사용자의 찜 상태 확인
        boolean isLiked = false;
        if (currentUserId != null) {
            // 사용자의 찜 상태 확인
            isLiked = productLikeRepository.findByUserIdAndProductId(currentUserId, productId).isPresent();
        }

        // 모든 이미지 URL, id 조회
        List<String> imageUrls = productImageRepository.findAllImageUrlsByProductId(productId);
        List<Long> imageIds = productImageRepository.findAllImageIdsByProductId(productId);

        User writer = product.getUser();
        UserDto userDto = null;
        if (writer != null) {
            userDto = UserDto.builder()
                    .id(writer.getId())
                    .nickname(writer.getNickname())
                    .build();
        }

        // DTO로 변환하여 반환
        return ProductDetailDto.builder()
                .id(product.getId())
                .title(product.getTitle())
                .description(product.getDescription())
                .price(product.getPrice())
                .location(product.getLocation())
                .productImageUrls(imageUrls) // 이미지 URL 리스트 사용
                .productImageIds(imageIds) // 이미지 ids
                .status(product.getStatus())
                .views(product.getViews())
                .likeCount(likeCount)
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .user(userDto) // userDto 포함 필요한 거만 뽑아서 쓰기
                .sportName(product.getSport().getName())
                .isLiked(isLiked)
                .build();
    }

    // 페이지에서 제품 리스트 조회
    // 종목 있는 경우도 여기서 처리
    @Transactional(readOnly = true)
    public Page<ProductListDto> getProductList(Optional<Long> sportId, Pageable pageable) {
        Page<Product> productPage;

        if (sportId.isPresent()) {
            // 카테고리가 있을 경우 - 해당 카테고리만 조회
            productPage = productRepository.findAllBySportIdWithSport(sportId.get(), pageable);
        } else {
            // 카테고리가 없을 경우- 전체 조회
            productPage = productRepository.findAllWithSport(pageable);
        }

        // DTO로 변환
        return productPage.map(product -> {
            long likeCount = productLikeRepository.countByProductId(product.getId());

            String thumbnailUrl = productImageRepository.findFirstByProductIdOrderByIdAsc(product.getId())
                    .map(ProductImage::getS3Url) // ProductImage 객체에서 s3Url 추출
                    .orElse("default_image.jpg"); // 기본 이미지 설정

            return ProductListDto.builder()
                    .id(product.getId())
                    .title(product.getTitle())
                    .description(product.getDescription())
                    .price(product.getPrice())
                    .location(product.getLocation())
                    .sportName(product.getSport().getName())
                    .views(product.getViews())
                    .likeCount(likeCount)
                    .status(product.getStatus())
                    .thumbnailUrl(thumbnailUrl)
                    .createdAt(product.getCreatedAt())
                    .updatedAt(product.getUpdatedAt())
                    .build();
        });
    }

    // 게시글 생성 및 이미지 정보 저장
    @Transactional
    public Long createProduct(ProductCreateDto request, List<MultipartFile> imageFiles) {

        // 유저, 종목 조회
        User author = userRepository.findById(request.getAuthorId())
                .orElseThrow(() -> new IllegalArgumentException("작성자를 찾을 수 없습니다. ID: " + request.getAuthorId()));

        Sport sport = sportRepository.findById(request.getSportId())
                .orElseThrow(() -> new IllegalArgumentException("종목을 찾을 수 없습니다. ID: " + request.getSportId()));

        // Product 엔티티 생성 및 저장
        Product product = Product.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .price(request.getPrice())
                .location(request.getLocation())
                .sport(sport)
                .user(author) // author_idx 컬럼에 매핑
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .views(0L)
                .build();

        Product savedProduct = productRepository.save(product);

        // 이미지 저장 및 ProductImage 엔티티 생성
        if (imageFiles != null && !imageFiles.isEmpty()) {
            for (MultipartFile file : imageFiles) {
                try {
                    // Product ID와 파일 객체를 넘겨 업로드 및 DB 저장을 위임
                    productImageService.uploadPhoto(savedProduct.getId(), file);
                } catch (IOException e) {
                    // 파일 업로드 실패 시 트랜잭션을 롤백하거나 적절히 예외 처리
                    throw new RuntimeException("이미지 업로드 중 오류 발생", e);
                }
            }
        }

        return savedProduct.getId();
    }

    // 물품 수정
    @Transactional
    public Long updateProduct(Long productId, ProductUpdateDto request, List<MultipartFile> newImageFiles) {
        // 제품 엔티티 조회
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("제품을 찾을 수 없습니다. ID: " + productId));

        Sport sport = sportRepository.findById(request.getSportId())
                .orElseThrow(() -> new IllegalArgumentException("종목을 찾을 수 없습니다. ID: " + request.getSportId()));

        product.updateProduct(request, sport);

        // 기존의 이미지 삭제
        if (request.getDeletedImageIds() != null && !request.getDeletedImageIds().isEmpty()) {
            request.getDeletedImageIds().forEach(imageId -> {
                try {
                    // S3 파일 삭제 및 DB 레코드 삭제를 위임
                    productImageService.deletePhoto(imageId);
                } catch (Exception e) {
                    // 삭제 실패 예외 처리 (로그 기록 등)
                    System.err.println("이미지 삭제 중 오류 발생 (ID: " + imageId + "): " + e.getMessage());
                }
            });
        }

        // 수정된 새로운 이미지 추가
        if (newImageFiles != null && !newImageFiles.isEmpty()) {
            for (MultipartFile file : newImageFiles) {
                try {
                    // Product ID와 파일 객체를 넘겨 업로드 및 DB 저장을 위임
                    productImageService.uploadPhoto(productId, file);
                } catch (IOException e) {
                    throw new RuntimeException("새 이미지 업로드 중 오류 발생", e);
                }
            }
        }

        return product.getId();
    }

    // 통합 검색 메서드
    @Transactional(readOnly = true)
    public Page<ProductListDto> searchProducts(Optional<Long> sportId, String keyword, Pageable pageable) {

        // sportId가 없으면 전체 종목 검색
        Long targetSportId = sportId.orElse(null);

        // 검색 키워드 null값일 시에 로직
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new IllegalArgumentException("검색 키워드는 필수입니다.");
        }

        // Repository 호출
        Page<Product> productPage =
                productRepository.searchByKeywordAndSport(targetSportId, keyword, pageable);

        // dto 변환 로직
        return productPage.map(product -> {
            long likeCount = productLikeRepository.countByProductId(product.getId());
            String thumbnailUrl = productImageRepository.findFirstByProductIdOrderByIdAsc(product.getId())
                    .map(ProductImage::getS3Url) // ProductImage 객체에서 s3Url 추출
                    .orElse("default_image.jpg");

            return ProductListDto.builder()
                    .id(product.getId())
                    .title(product.getTitle())
                    .description(product.getDescription())
                    .price(product.getPrice())
                    .location(product.getLocation())
                    .sportName(product.getSport().getName())
                    .views(product.getViews())
                    .likeCount(likeCount)
                    .status(product.getStatus())
                    .thumbnailUrl(thumbnailUrl)
                    .createdAt(product.getCreatedAt())
                    .updatedAt(product.getUpdatedAt())
                    .build();
        });
    }

    // 게시물 논리적 삭제 메서드
    @Transactional
    public void deleteProductSoftly(Long productId, Long currentUserId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("게시물을 찾을 수 없습니다. ID: " + productId));

        // 권한 확인
        if (!product.getUser().getId().equals(currentUserId)) {
            throw new IllegalArgumentException("게시물 작성자만 삭제할 수 있습니다.");
        }

        // DELETE로 상태 변경
        product.updateStatus(TransactionStatus.DELETE);
    }

    // 게시물 status 변경
    @Transactional
    public void changeProductStatus(Long productId, Long currentUserId, TransactionStatus newStatus) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("게시물을 찾을 수 없습니다. ID: " + productId));

        // 권한 확인
        if (!product.getUser().getId().equals(currentUserId)) {
            throw new IllegalArgumentException("게시물 작성자만 수정할 수 있습니다.");
        }

        // 사용자가 원하는 상태로 변경
        // SELLING("판매중"),
        // RESERVED("예약중"),
        // SOLD("거래완료"),
        product.updateStatus(newStatus);
    }

    // 내 판매 게시물
    @Transactional(readOnly = true)
    public Page<ProductListDto> getMySellingProducts(Long authorId, String statusString, Pageable pageable) {
        // 상태 문자열 변환
        TransactionStatus statusEnum = TransactionStatus.valueOf(statusString.toUpperCase());
        // Repository 호출
        Page<Product> productPage =
                productRepository.findAllByAuthorIdAndStatus(authorId, statusEnum, pageable);

        // DTO 변환 로직 (기존 getProductList와 동일)
        return productPage.map(product -> {
            long likeCount = productLikeRepository.countByProductId(product.getId());
            String thumbnailUrl = productImageRepository.findFirstByProductIdOrderByIdAsc(product.getId())
                    .map(ProductImage::getS3Url)
                    .orElse("default_image.jpg");

            return ProductListDto.builder()
                    .id(product.getId())
                    .title(product.getTitle())
                    .description(product.getDescription())
                    .price(product.getPrice())
                    .location(product.getLocation())
                    .sportName(product.getSport().getName())
                    .views(product.getViews())
                    .likeCount(likeCount)
                    .thumbnailUrl(thumbnailUrl)
                    .createdAt(product.getCreatedAt())
                    .updatedAt(product.getUpdatedAt())
                    .build();
        });
    }
}
