package com.example.muscle_market.controller.view;

import com.example.muscle_market.domain.CustomUserDetails;
import com.example.muscle_market.dto.*;
import com.example.muscle_market.service.ProductLikeService;
import com.example.muscle_market.service.ProductService;
import com.example.muscle_market.service.SportService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
@RequestMapping("/products")
public class ProductPageController {
    private final ProductService productService;
    private final SportService sportService;

    // 한 페이지에 표시할 아이템 수 (가로 4줄 * 세로 5줄 = 20개)
    private static final int PAGE_SIZE = 20;
    private final ProductLikeService productLikeService;

    @GetMapping
    public String productList(
            // 현재 페이지 번호 (0부터 시작, 기본값 0)
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Long sportId,
            Model model) {

        // Pageable 객체 생성
        // 현재 페이지, 페이지 크기, 정렬 기준 (최신순)
        Pageable pageable = PageRequest.of(page, PAGE_SIZE, Sort.by("createdAt").descending());

        // 서비스, 종목 드랍다운에 들어갈 카테고리
        Page<ProductListDto> productPage = productService.getProductList(Optional.ofNullable(sportId), pageable);
        List<SportDto> sports = sportService.getAllSports();
        String selectedSportName = sportId != null
                ? sports.stream().filter(s -> s.getId().equals(sportId)).findFirst().map(SportDto::getName).orElse("전체 종목")
                : "전체 종목";

        // 데이터 전달
        model.addAttribute("productPage", productPage);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", productPage.getTotalPages());

        // 페이지네이션 처리를 위한 정보 추가
        model.addAttribute("sports", sports);
        model.addAttribute("currentSportId", sportId);
        model.addAttribute("selectedSportName", selectedSportName);

        // 찜꽁 리스트에서는 카테고리 드랍다운을 숨김 차별점을 주어야 함
        model.addAttribute("isLikePage", false);
        return "productlist";
    }

    // 신규 게시물 등록
    @GetMapping("/new")
    public String newProductForm(Model model, @AuthenticationPrincipal CustomUserDetails authUser) {
        // 사용자 확인
        if (authUser != null) {
            SimplifiedUserDto currentUser = SimplifiedUserDto.builder()
                    .userId(authUser.getId())
                    .username(authUser.getUsername())
                    .nickname(authUser.getNickname())
                    .profileImgUrl(authUser.getProfileImgUrl())
                    .build();
            model.addAttribute("currentUser", currentUser);
        } else {
            model.addAttribute("currentUser", null);
        }

        // sport 목록
        model.addAttribute("sports", sportService.getAllSports());
        return "product_form";
    }

    // 게시물 상세 페이지 반환 매핑
    @GetMapping("/{productId}")
    public String productDetail(@PathVariable Long productId, Model model, @AuthenticationPrincipal CustomUserDetails principal) {
        try {
            // 게시물 상세 DTO get
            ProductDetailDto dto = productService.getProductDetail(productId, principal.getId());
            model.addAttribute("dto", dto);

            return "product_detail";
        } catch (IllegalArgumentException e) {
            // status가 DELETE인 게시물에 대한 처리
            System.err.println("Product Access Error: " + e.getMessage());

            // 목록 페이지로 리다이렉트
            return "redirect:/products";
        }
    }

    @GetMapping("/search")
    public String searchProducts(
            @RequestParam String keyword, // 필수 검색 키워드
            @RequestParam(required = false) Long sportId, // 선택적 카테고리 필터
            @RequestParam(defaultValue = "0") int page,

            Model model) {

        // Pageable 객체 생성
        Pageable pageable = PageRequest.of(page, PAGE_SIZE, Sort.by("createdAt").descending());

        // 서비스 호출
        Page<ProductListDto> productPage =
                productService.searchProducts(Optional.ofNullable(sportId), keyword, pageable);

        // 모델에 데이터 먹이기
        model.addAttribute("productPage", productPage);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", productPage.getTotalPages());

        // 검색 결과를 뷰에 전달하기 위한 추가 정보
        model.addAttribute("currentKeyword", keyword); // 검색 결과 뷰에 키워드 유지
        model.addAttribute("currentSportId", sportId); // 카테고리 필터 유지 (페이지네이션을 위해)

        // 찜꽁 페이지가 아님
        // 왜 이부분이 안올라갈까
        model.addAttribute("isLikePage", false);

        // productlist.html 재사용
        return "productlist";
    }

    @GetMapping("/my")
    public String getMyPosts(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestParam(defaultValue = "SELLING") String status,
            @RequestParam(defaultValue = "0") int page,
            Model model) {

        // Pageable 객체 생성
        Pageable pageable = PageRequest.of(page, PAGE_SIZE, Sort.by("createdAt").descending());

        // 서비스 호출
        Page<ProductListDto> productPage =
                productService.getMySellingProducts(principal.getId(), status, pageable);

        // 모델 전달 (productlist.html 재사용)
        model.addAttribute("productPage", productPage);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", productPage.getTotalPages());

        // 현재 상태
        model.addAttribute("currentStatus", status);
        // 기존의 상품 목록 템플릿 재사용
        return "my_productlist";
    }

    @GetMapping("/likes")
    public String getMyLikedProducts(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestParam(defaultValue = "0") int page,
            Model model) {

        Long userId = principal.getId();

        Pageable pageable = PageRequest.of(page, PAGE_SIZE, Sort.by("id").descending());

        // 서비스 호출
        Page<ProductListDto> productPage =
                productLikeService.getLikedProducts(userId, pageable);

        // 종목 목록
        List<SportDto> sports = sportService.getAllSports();

        // 모델에 데이터 추가
        model.addAttribute("productPage", productPage);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", productPage.getTotalPages());
        model.addAttribute("sports", sports);

        // 현재 페이지가 좋아요 페이지임을 알리는 플래그
        model.addAttribute("isLikePage", true);

        return "productlist";
    }

    // 수정 페이지
    @GetMapping("/edit/{productId}")
    public String editProductForm(
            @PathVariable Long productId,
            Model model,
            @AuthenticationPrincipal CustomUserDetails principal) {

        // 권한 확인 로그인으로 가라
        if (principal == null) {
            return "redirect:/login";
        }
        Long currentUserId = principal.getId();
        // 기존 상품 조회 및 가져오기
        ProductDetailDto dto = productService.getProductDetail(productId, currentUserId);

        // 작성자와 현재 사용자 대조
        if (!dto.getUser().getId().equals(currentUserId)) {
            return "redirect:/products";
        }

        model.addAttribute("dto", dto);
        model.addAttribute("sports", sportService.getAllSports());
        model.addAttribute("currentUserId", currentUserId);

        // 이걸 보냄으로써 편집 창이 띄워진다
        model.addAttribute("isEditMode", true);
        model.addAttribute("productId", productId);

        return "product_form";
    }
}
