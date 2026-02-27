// api-service — product catalog API for MCP benchmark v3.
// Pure Go stdlib, no external dependencies.
// Builds a 100k-product catalog at startup and serves:
//   GET  /products/search?category=&min_price=&max_price=&limit=
//   GET  /products/{id}
//   POST /cart/calculate
//   GET  /health
package main

import (
	"encoding/json"
	"fmt"
	"log"
	"math"
	"net/http"
	"sort"
	"strconv"
	"strings"
)

// ─── Domain types ─────────────────────────────────────────────────────────────

type Product struct {
	ID          uint32   `json:"id"`
	SKU         string   `json:"sku"`
	Name        string   `json:"name"`
	Category    string   `json:"category"`
	Brand       string   `json:"brand"`
	Price       float64  `json:"price"`
	Stock       int32    `json:"stock"`
	Rating      float32  `json:"rating"`
	Tags        []string `json:"tags"`
	Weight      float64  `json:"weight"`
	Description string   `json:"description"`
}

type CartItem struct {
	ProductID int `json:"product_id"`
	Quantity  int `json:"quantity"`
}

type CartRequest struct {
	UserID string     `json:"user_id"`
	Items  []CartItem `json:"items"`
}

type CartResponse struct {
	OrderID    string  `json:"order_id"`
	UserID     string  `json:"user_id"`
	Subtotal   float64 `json:"subtotal"`
	Tax        float64 `json:"tax"`
	Shipping   float64 `json:"shipping"`
	Discount   float64 `json:"discount"`
	Total      float64 `json:"total"`
	ItemsCount int     `json:"items_count"`
}

type SearchResponse struct {
	Category   string    `json:"category"`
	TotalFound int       `json:"total_found"`
	Products   []Product `json:"products"`
}

// ─── Catalog ──────────────────────────────────────────────────────────────────

var (
	categories20 = []string{
		"Electronics", "Books", "Clothing", "Sports", "Home",
		"Garden", "Toys", "Food", "Beauty", "Automotive",
		"Music", "Movies", "Games", "Office", "Health",
		"Pets", "Travel", "Tools", "Jewelry", "Baby",
	}
	brands50 = []string{
		"Alpha", "Beta", "Gamma", "Delta", "Epsilon",
		"Zeta", "Eta", "Theta", "Iota", "Kappa",
		"Lambda", "Mu", "Nu", "Xi", "Omicron",
		"Pi", "Rho", "Sigma", "Tau", "Upsilon",
		"Phi", "Chi", "Psi", "Omega", "Apex",
		"Nexus", "Vertex", "Zenith", "Nadir", "Flux",
		"Forge", "Craft", "Build", "Make", "Create",
		"Design", "Style", "Form", "Shape", "Mold",
		"Cast", "Weld", "Fuse", "Bond", "Link",
		"Chain", "Wire", "Mesh", "Weave", "Knit",
	}
	tagPool = []string{
		"sale", "new", "featured", "bestseller",
		"clearance", "limited", "popular", "exclusive",
	}
)

var catalog []Product
var byCategory map[string][]int32

func r2(f float64) float64 { return math.Round(f*100) / 100 }

func init() {
	catalog = make([]Product, 100_000)
	byCategory = make(map[string][]int32)

	for i := 0; i < 100_000; i++ {
		cat := categories20[i%20]
		brand := brands50[i%50]
		// Deterministic seed formula (matches plan exactly)
		price := 1.0 + float64(i%99900)/100.0
		stock := int32(i % 501)
		rating := float32(1.0) + float32(i%40)/10.0

		numTags := 2 + i%3
		tags := make([]string, numTags)
		for j := 0; j < numTags; j++ {
			tags[j] = tagPool[(i+j)%8]
		}

		catalog[i] = Product{
			ID:          uint32(i + 1),
			SKU:         fmt.Sprintf("SKU-%06d", i+1),
			Name:        fmt.Sprintf("%s %s Item %d", brand, cat, i+1),
			Category:    cat,
			Brand:       brand,
			Price:       price,
			Stock:       stock,
			Rating:      rating,
			Tags:        tags,
			Weight:      r2(float64(1+i%100) * 0.1),
			Description: fmt.Sprintf("Quality %s by %s. SKU %06d.", cat, brand, i+1),
		}
		byCategory[cat] = append(byCategory[cat], int32(i)) // 0-indexed
	}
	log.Printf("Catalog ready: %d products across %d categories", len(catalog), len(byCategory))
}

// ─── Handlers ─────────────────────────────────────────────────────────────────

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func healthHandler(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, map[string]string{"status": "ok", "server_type": "api-service"})
}

func searchHandler(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	category := q.Get("category")
	if category == "" {
		category = "Electronics"
	}
	minPrice, _ := strconv.ParseFloat(q.Get("min_price"), 64)
	maxPrice, _ := strconv.ParseFloat(q.Get("max_price"), 64)
	if maxPrice == 0 {
		maxPrice = math.MaxFloat64
	}
	limit, _ := strconv.Atoi(q.Get("limit"))
	if limit <= 0 || limit > 1000 {
		limit = 10
	}

	indices, ok := byCategory[category]
	if !ok {
		writeJSON(w, SearchResponse{Category: category, TotalFound: 0, Products: []Product{}})
		return
	}

	var matching []Product
	for _, idx := range indices {
		p := catalog[idx]
		if p.Price >= minPrice && p.Price <= maxPrice {
			matching = append(matching, p)
		}
	}

	// Sort by price ascending for deterministic results
	sort.Slice(matching, func(i, j int) bool {
		return matching[i].Price < matching[j].Price
	})

	totalFound := len(matching)
	if limit < len(matching) {
		matching = matching[:limit]
	}

	writeJSON(w, SearchResponse{
		Category:   category,
		TotalFound: totalFound,
		Products:   matching,
	})
}

func getProductHandler(w http.ResponseWriter, r *http.Request) {
	// Extract {id} from path: /products/{id}
	path := strings.TrimPrefix(r.URL.Path, "/products/")
	id, err := strconv.Atoi(path)
	if err != nil || id < 1 || id > 100_000 {
		http.NotFound(w, r)
		return
	}
	writeJSON(w, catalog[id-1])
}

func cartHandler(w http.ResponseWriter, r *http.Request) {
	var req CartRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}

	subtotal := 0.0
	for _, item := range req.Items {
		if item.ProductID < 1 || item.ProductID > 100_000 {
			continue
		}
		p := catalog[item.ProductID-1]
		subtotal += p.Price * float64(item.Quantity)
	}
	subtotal = r2(subtotal)

	tax := r2(subtotal * 0.085)
	shipping := 5.99
	discount := 0.0
	if subtotal >= 100.0 {
		discount = r2(subtotal * 0.10)
		shipping = 0.0
	}
	total := r2(subtotal + tax + shipping - discount)

	orderID := fmt.Sprintf("ORD-%s-%d", strings.ReplaceAll(req.UserID, "-", ""), len(req.Items))

	writeJSON(w, CartResponse{
		OrderID:    orderID,
		UserID:     req.UserID,
		Subtotal:   subtotal,
		Tax:        tax,
		Shipping:   shipping,
		Discount:   discount,
		Total:      total,
		ItemsCount: len(req.Items),
	})
}

// ─── Main ─────────────────────────────────────────────────────────────────────

func main() {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", healthHandler)
	mux.HandleFunc("GET /products/search", searchHandler)
	mux.HandleFunc("GET /products/{id}", getProductHandler)
	mux.HandleFunc("POST /cart/calculate", cartHandler)

	log.Println("api-service listening on :8100")
	if err := http.ListenAndServe(":8100", mux); err != nil {
		log.Fatal(err)
	}
}
